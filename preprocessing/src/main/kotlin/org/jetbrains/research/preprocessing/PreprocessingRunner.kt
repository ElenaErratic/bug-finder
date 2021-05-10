package org.jetbrains.research.preprocessing

import com.github.gumtreediff.actions.ActionGenerator
import com.github.gumtreediff.actions.model.*
import com.github.gumtreediff.matchers.Matchers
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.PyElement
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.SystemExitException
import com.xenomachina.argparser.default
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.research.common.PatternGraph
import org.jetbrains.research.common.buildPyFlowGraphForMethod
import org.jetbrains.research.common.gumtree.PyPsiGumTree
import org.jetbrains.research.common.gumtree.PyPsiGumTreeGenerator
import org.jetbrains.research.common.gumtree.wrappers.ActionWrapper
import org.jetbrains.research.common.jgrapht.PatternGraph
import org.jetbrains.research.common.jgrapht.getSuperWeakSubgraphIsomorphismInspector
import org.jetbrains.research.common.jgrapht.getWeakSubgraphIsomorphismInspector
import org.jetbrains.research.common.jgrapht.vertices.PatternSpecificVertex
import org.jetbrains.research.preprocessing.loaders.CachingPsiLoader
import org.jetbrains.research.preprocessing.models.Pattern
import org.jgrapht.graph.AsSubgraph
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.system.exitProcess


class PreprocessingRunner : ApplicationStarter {
    private lateinit var sourceDir: Path
    private lateinit var destDir: Path
    private var addDescription: Boolean = false

    private lateinit var myProject: Project
    private val logger = Logger.getInstance(this::class.java)

    private val descriptionByPath = HashMap<Path, String>()
    private val labelsGroupsJsonByPath = HashMap<Path, String>()
    private val reprFragmentByPatternPath = HashMap<Path, PatternGraph>()

    private val fragmentToPatternMappingByPattern =
        HashMap<String, HashMap<PatternSpecificVertex, PatternSpecificVertex>>()
    private val psiToPatternMappingByPattern =
        HashMap<String, HashMap<PyElement, PatternSpecificVertex>>()

    private val patternGraphCache = HashMap<String, PatternGraph>()
    private val fragmentGraphCache = HashMap<String, PatternGraph>()
    private val actionsCache = HashMap<String, List<Action>>()


    override fun getCommandName(): String = "preprocessing"

    class PreprocessingRunnerArgs(parser: ArgParser) {
        val source by parser.storing(
            "-s", "--src",
            help = "path/to/patterns/mined/by/code-change-miner"
        )

        val destination by parser.storing(
            "-d", "--dest",
            help = "path/to/destination"
        )

        val desc by parser.flagging(
            "-a", "--addDescription",
            help = "add description to each pattern, will be shown in the IDE (optional)"
        ).default(false)
    }

    override fun main(args: Array<out String>) {
        try {
            ArgParser(args.drop(1).toTypedArray()).parseInto(::PreprocessingRunnerArgs).run {
                sourceDir = Paths.get(source)
                destDir = Paths.get(destination)
                addDescription = desc
            }

            sourceDir.toFile().listFiles()?.forEach { patternDir ->
                myProject = ProjectUtil.openOrImport(patternDir.toPath(), null, true)
                    ?: throw IllegalStateException("Can not import or create project")
                val pattern = Pattern(directory = patternDir.toPath(), project = myProject)

                val targetDirectory = destDir.resolve(pattern.name)
                targetDirectory.toFile().mkdirs()
                pattern.save(targetDirectory)
            }
        } catch (ex: SystemExitException) {
            logger.error(ex)
        } catch (ex: Exception) {
            logger.error(ex)
        } finally {
            exitProcess(0)
        }
    }

    /**
     * Loads all `fragment-[0-9]*.dot` files from the specified directory. Such directory should
     * contain patterns mined by the code-change-miner tool.
     *
     * @return mappings from concrete pattern's directory to its fragments.
     */
    private fun loadFragments(inputPatternsStorage: Path): HashMap<Path, ArrayList<PatternGraph>> {
        val fragmentsByDir = HashMap<Path, ArrayList<PatternGraph>>()
        inputPatternsStorage.toFile().walk().forEach { file ->
            if (file.isFile && file.name.startsWith("fragment") && file.extension == "dot") {
                val currentGraph = PatternGraph(file.inputStream())
                val subgraphBefore = AsSubgraph(
                    currentGraph,
                    currentGraph.vertexSet()
                        .filter { it.fromPart == PatternSpecificVertex.ChangeGraphPartIndicator.BEFORE }
                        .toSet()
                )
                fragmentsByDir.getOrPut(Paths.get(file.parent)) { ArrayList() }.add(subgraphBefore)
            }
        }
        return fragmentsByDir
    }

    /**
     * Checks whether the loaded fragments for each pattern are isomorphic between each other
     * and picks the first fragment as a representative element for corresponding pattern.
     *
     * @return mappings from concrete pattern's directory to the pattern's graph representation.
     */
    private fun mergeFragments(fragmentsMap: HashMap<Path, ArrayList<PatternGraph>>): HashMap<Path, PatternGraph> {
        val patternGraphByPath = HashMap<Path, PatternGraph>()
        for ((path, fragments) in fragmentsMap) {
            val labelsGroupsByVertexId = HashMap<Int, PatternSpecificVertex.LabelsGroup>()
            val repr = fragments.first()
            reprFragmentByPatternPath[path] = repr
            for (graph in fragments) {
                val inspector = getSuperWeakSubgraphIsomorphismInspector(repr, graph)
                if (!inspector.isomorphismExists()) {
                    throw IllegalStateException("Fragments are not isomorphic in the pattern $path")
                } else {
                    val mapping = inspector.mappings.asSequence().first()
                    for (currentVertex in graph.vertexSet()) {
                        if (currentVertex.label?.startsWith("var") == true) {
                            val reprVertex = mapping.getVertexCorrespondence(currentVertex, false)
                            labelsGroupsByVertexId.getOrPut(reprVertex.id)
                            { PatternSpecificVertex.LabelsGroup.getEmpty() }
                                .labels.add(currentVertex.originalLabel!!)
                        }
                    }
                }
            }
            patternGraphByPath[path] = PatternGraph(fragments.first(), labelsGroupsByVertexId)
        }
        return patternGraphByPath
    }

    private fun markPatternsManually(
        patternDirectedAcyclicGraphByPath: HashMap<Path, PatternGraph>,
        addDescription: Boolean = false
    ) {
        println("Start marking")
        for ((path, graph) in patternDirectedAcyclicGraphByPath) {
            println("-".repeat(70))
            println("Path to current pattern: $path")

            // Add description, which will be shown in the popup (optional)
            if (addDescription) {
                val dotFile = path.toFile()
                    .listFiles { file -> file.extension == "dot" && file.name.startsWith("fragment") }
                    ?.first()
                println("Fragment sample ${dotFile?.name}")
                println(dotFile?.readText())
                println("Your description:")
                val description = readLine() ?: "No description provided"
                println("Final description: $description")
                descriptionByPath[path] = description
            }

            // Choose matching mode
            println("Variable original labels groups:")
            val labelsGroupsByVertexId = HashMap<Int, PatternSpecificVertex.LabelsGroup>()
            for (vertex in graph.vertexSet()) {
                if (vertex.label?.startsWith("var") == true) {
                    println(vertex.dataNodeInfo?.labels)
                    var exit = false
                    while (!exit) {
                        println("Choose, what will be considered as main factor when matching nodes (labels/lcs/nothing):")
                        val ans = BufferedReader(InputStreamReader(System.`in`)).readLine()
                        println("Your answer: $ans")
                        when (ans) {
                            "labels" -> labelsGroupsByVertexId[vertex.id] =
                                PatternSpecificVertex.LabelsGroup(
                                    whatMatters = PatternSpecificVertex.MatchingMode.VALUABLE_ORIGINAL_LABEL,
                                    labels = vertex.dataNodeInfo!!.labels,
                                    longestCommonSuffix = ""
                                ).also { exit = true }
                            "lcs" -> labelsGroupsByVertexId[vertex.id] =
                                PatternSpecificVertex.LabelsGroup(
                                    whatMatters = PatternSpecificVertex.MatchingMode.LONGEST_COMMON_SUFFIX,
                                    labels = vertex.dataNodeInfo!!.labels,
                                    longestCommonSuffix = getLongestCommonSuffix(vertex.dataNodeInfo?.labels)
                                ).also { exit = true }
                            "nothing" -> labelsGroupsByVertexId[vertex.id] =
                                PatternSpecificVertex.LabelsGroup(
                                    whatMatters = PatternSpecificVertex.MatchingMode.NOTHING,
                                    labels = vertex.dataNodeInfo!!.labels,
                                    longestCommonSuffix = ""
                                ).also { exit = true }
                        }
                    }
                }
            }
            labelsGroupsJsonByPath[path] = Json.encodeToString(labelsGroupsByVertexId)
        }
        println("Finish marking")
    }

    private fun markPatternsAutomatically(
        patternDirectedAcyclicGraphByPath: HashMap<Path, PatternGraph>,
        addDescription: Boolean = false
    ) {
        println("Start marking")
        for ((path, graph) in patternDirectedAcyclicGraphByPath) {
            println("-".repeat(70))
            println("Path to current pattern: $path")

            // Add description, which will be shown in the popup (optional)
            if (addDescription) {
                val dotFile = path.toFile()
                    .listFiles { file -> file.extension == "dot" && file.name.startsWith("fragment") }
                    ?.first()
                println("Fragment sample ${dotFile?.name}")
                println(dotFile?.readText())
                println("Your description:")
                val description = readLine() ?: "No description provided"
                println("Final description: $description")
                descriptionByPath[path] = description
            }

            // Choose matching mode automatically
            val labelsGroupsByVertexId = HashMap<Int, PatternSpecificVertex.LabelsGroup>()
            for (vertex in graph.vertexSet()) {
                if (vertex.label?.startsWith("var") == true) {
                    val labels = vertex.dataNodeInfo?.labels ?: hashSetOf()
                    val lcs = getLongestCommonSuffix(labels)
                    when {
                        lcs.contains('.') -> {
                            // If all the labels have common attribute at the end, such as `.size`
                            labelsGroupsByVertexId[vertex.id] = PatternSpecificVertex.LabelsGroup(
                                whatMatters = PatternSpecificVertex.MatchingMode.LONGEST_COMMON_SUFFIX,
                                labels = labels,
                                longestCommonSuffix = lcs
                            )
                        }
                        labels.all { it.contains('.') } -> {
                            // If the labels do not have any common attribute suffix, but still have some attributes,
                            // we should save their original labels as well
                            labelsGroupsByVertexId[vertex.id] = PatternSpecificVertex.LabelsGroup(
                                whatMatters = PatternSpecificVertex.MatchingMode.VALUABLE_ORIGINAL_LABEL,
                                labels = labels,
                                longestCommonSuffix = ""
                            )
                        }
                        else -> {
                            // Otherwise, just suppose that this vertex corresponds to a variable,
                            // so its name does not matter
                            labelsGroupsByVertexId[vertex.id] =
                                PatternSpecificVertex.LabelsGroup(
                                    whatMatters = PatternSpecificVertex.MatchingMode.NOTHING,
                                    labels = vertex.dataNodeInfo!!.labels,
                                    longestCommonSuffix = ""
                                )
                        }
                    }
                }
            }
            labelsGroupsJsonByPath[path] = Json.encodeToString(labelsGroupsByVertexId)
        }
        println("Finish marking")
    }

    /**
     * Creates mapping from PSI of the representative fragment to the pattern's graph vertices,
     * also creates mapping between repr fragment's graph vertices and pattern's graph vertices,
     * also save repr fragment's graph to the local cache
     */
    private fun createFragmentToPatternMappings(patternDir: File) {
        var reprFragmentGraph: PatternGraph? = null
        for (file in patternDir.walk()) {
            if (file.name.startsWith("sample") && file.extension == "html") {
                val document = Jsoup.parse(file.readText())
                val codeElements = document.getElementsByClass("code language-python")
                assert(codeElements.size == 2)

                val fragmentCodeBefore = codeElements[0].text()
                val psiBefore = CachingPsiLoader.getInstance(myProject).loadPsiFromSource(fragmentCodeBefore)
                reprFragmentGraph = buildPyFlowGraphForMethod(psiBefore)
                break
            }
        }
        fragmentGraphCache[patternDir.name] = reprFragmentGraph
            ?: throw IllegalStateException("Can not find any sample-[0-9]*.html file in the directory")

        val patternGraph = loadPatternGraph(patternDir)
        val inspector = getWeakSubgraphIsomorphismInspector(reprFragmentGraph, patternGraph)

        val fragmentToPatternMapping = HashMap<PatternSpecificVertex, PatternSpecificVertex>()
        val psiToPatternMapping = HashMap<PyElement, PatternSpecificVertex>()

        var foundCorrectMapping = false
        if (inspector.isomorphismExists()) {
            for (mapping in inspector.mappings.asSequence()) {
                var isCorrectMapping = true
                for (patternVertex in patternGraph.vertexSet()) {
                    val fragmentVertex = mapping.getVertexCorrespondence(patternVertex, false)
                    if (patternVertex.originalLabel?.toLowerCase() != fragmentVertex.originalLabel?.toLowerCase()) {
                        isCorrectMapping = false
                        break
                    }
                    fragmentToPatternMapping[fragmentVertex] = patternVertex
                    psiToPatternMapping[fragmentVertex.origin!!.psi!!] = patternVertex
                }
                if (isCorrectMapping) {
                    fragmentToPatternMappingByPattern[patternDir.name] = fragmentToPatternMapping
                    psiToPatternMappingByPattern[patternDir.name] = psiToPatternMapping
                    foundCorrectMapping = true
                    break
                }
            }
        }
        if (!foundCorrectMapping) {
            throw IllegalStateException("Pattern's fragments do not match between each other")
        }
    }

    private fun loadPatternGraph(patternDir: File): PatternGraph {
        return if (patternGraphCache.containsKey(patternDir.name)) {
            patternGraphCache[patternDir.name]!!
        } else {
            val changeGraph = reprFragmentByPatternPath[patternDir.toPath()]!!
            val subgraphBefore = AsSubgraph(
                changeGraph,
                changeGraph.vertexSet()
                    .filter { it.fromPart == PatternSpecificVertex.ChangeGraphPartIndicator.BEFORE }
                    .toSet()
            )
            val labelsGroups = Json.decodeFromString<HashMap<Int, PatternSpecificVertex.LabelsGroup>>(
                labelsGroupsJsonByPath[patternDir.toPath()]!!
            )
            val graph = PatternGraph(subgraphBefore, labelsGroups)
            patternGraphCache[patternDir.name] = graph
            graph
        }
    }

    private fun loadEditActions(patternDir: File): List<Action> =
        if (actionsCache.containsKey(patternDir.name)) {
            actionsCache[patternDir.name]!!
        } else {
            val actionsGroups: MutableList<List<Action>> = arrayListOf()

            patternDir.walk().forEach { file ->
                if (file.name.startsWith("sample") && file.extension == "html") {
                    val document = Jsoup.parse(file.readText())
                    val codeElements = document.getElementsByClass("code language-python")
                    assert(codeElements.size == 2)

                    val fragmentCodeBefore = codeElements[0].text()
                    val fragmentCodeAfter = codeElements[1].text()
                    val psiBefore = CachingPsiLoader.getInstance(myProject).loadPsiFromSource(fragmentCodeBefore)
                    val psiAfter = CachingPsiLoader.getInstance(myProject).loadPsiFromSource(fragmentCodeAfter)
                    val srcGumtree = PyPsiGumTreeGenerator().generate(psiBefore).root
                    val dstGumtree = PyPsiGumTreeGenerator().generate(psiAfter).root

                    val matcher = Matchers.getInstance().getMatcher(srcGumtree, dstGumtree).also { it.match() }
                    val generator = ActionGenerator(srcGumtree, dstGumtree, matcher.mappings)
                    val currentFragmentActions = generator.generate()
                    actionsGroups.add(currentFragmentActions)
                }
            }

            var reprActions = actionsGroups.first()
            for (actions in actionsGroups.drop(1)) {
                reprActions = getLongestCommonEditActionsSubsequence(reprActions, actions)
            }
            actionsCache[patternDir.name] = reprActions
            reprActions
        }


    /**
     * Collect PSI elements which are involved in edit actions but are not contained in the pattern's graph
     */
    private fun collectAdditionalElementsFromActions(patternDir: File): Set<PyElement> {
        val psiToPatternVertex = psiToPatternMappingByPattern[patternDir.name]!!
        val actions = loadEditActions(patternDir)
        val insertedElements = hashSetOf<PyElement>()
        val hangerElements = hashSetOf<PyElement>()
        for (action in actions) {
            val element = (action.node as PyPsiGumTree).rootElement!!
            if (action is Update || action is Delete || action is Move) {
                if (!psiToPatternVertex.containsKey(element))
                    hangerElements.add(element)
            }
            if (action is Insert) {
                val newElement = (action.node as PyPsiGumTree).rootElement ?: continue
                insertedElements.add(newElement)
            }
            if (action is Insert || action is Move) {
                val parent = (action as? Insert)?.parent ?: (action as? Move)?.parent
                val parentElement = (parent as PyPsiGumTree).rootElement!!
                if (insertedElements.contains(parentElement))
                    continue
                hangerElements.add(parentElement)
                if (!psiToPatternVertex.containsKey(parentElement))
                    hangerElements.add(parentElement)
            }
        }
        return hangerElements
    }

    /**
     * Add vertices (containing given PyElements) to pattern graph and connect them to all its neighbours,
     * because `VF2SubgraphIsomorphismMatcher` will match only among induced subgraphs
     */
    private fun extendPatternGraphWithElements(patternDir: File) {
        val patternGraph = loadPatternGraph(patternDir)
        val fragmentGraph = fragmentGraphCache[patternDir.name]!!
        val fragmentToPatternMapping = fragmentToPatternMappingByPattern[patternDir.name]!!
        val hangerElements = collectAdditionalElementsFromActions(patternDir)

        for (element in hangerElements) {
            val originalVertex = fragmentGraph.vertexSet().find { it.origin?.psi == element } ?: continue
            if (fragmentToPatternMapping.containsKey(originalVertex)
                && patternGraph.containsVertex(fragmentToPatternMapping[originalVertex])
            ) {
                continue
            }
            val newVertex = originalVertex.copy()
            psiToPatternMappingByPattern[patternDir.name]?.put(element, newVertex)
            fragmentToPatternMapping[originalVertex] = newVertex
            if (newVertex.label?.startsWith("var") == true) {
                newVertex.dataNodeInfo = PatternSpecificVertex.LabelsGroup(
                    whatMatters = PatternSpecificVertex.MatchingMode.NOTHING,
                    labels = hashSetOf(),
                    longestCommonSuffix = ""
                )
            }
            newVertex.metadata = "hanger"
            patternGraph.addVertex(newVertex)
            for (incomingEdge in fragmentGraph.incomingEdgesOf(originalVertex)) {
                val fragmentEdgeSource = fragmentGraph.getEdgeSource(incomingEdge)
                val patternEdgeSource = fragmentToPatternMapping[fragmentEdgeSource] ?: continue
                patternGraph.addEdge(patternEdgeSource, newVertex, incomingEdge)
            }
            for (outgoingEdge in fragmentGraph.outgoingEdgesOf(originalVertex)) {
                val fragmentEdgeTarget = fragmentGraph.getEdgeTarget(outgoingEdge)
                val patternEdgeTarget = fragmentToPatternMapping[fragmentEdgeTarget] ?: continue
                patternGraph.addEdge(newVertex, patternEdgeTarget, outgoingEdge)
            }
        }
    }

    /**
     * Swap `Update` and `Move` actions which keeps the node with the same type and label,
     * since it could produce bugs with updating already moved nodes
     */
    private fun sortActions(patternDir: File) {
        val actions = loadEditActions(patternDir)
        val updates = arrayListOf<Pair<Int, Update>>()
        for ((i, action) in actions.withIndex()) {
            if (action is Update) {
                updates.add(Pair(i, action))
                continue
            }
            if (action is Move) {
                val item = updates.find { it.second.node.hasSameTypeAndLabel(action.node) } ?: continue
                Collections.swap(actions, i, item.first)
            }
        }
    }

    /**
     * Add the corresponding `PatternSpecificVertex` node to each action, and serialize it
     */
    private fun serializeActions(patternDir: File): String {
        val psiToPatternVertex = psiToPatternMappingByPattern[patternDir.name]!!
        val actions = loadEditActions(patternDir)
        val actionsWrappers = arrayListOf<ActionWrapper>()
        for (action in actions) {
            val element = (action.node as PyPsiGumTree).rootElement!!
            when (action) {
                is Update -> {
                    (action.node as PyPsiGumTree).rootVertex = psiToPatternVertex[element]
                    actionsWrappers.add(ActionWrapper.UpdateActionWrapper(action))
                }
                is Delete -> {
                    (action.node as PyPsiGumTree).rootVertex = psiToPatternVertex[element]
                    actionsWrappers.add(ActionWrapper.DeleteActionWrapper(action))
                }
                is Insert -> {
                    val parentElement = (action.parent as PyPsiGumTree).rootElement!!
                    (action.parent as PyPsiGumTree).rootVertex = psiToPatternVertex[parentElement]
                    actionsWrappers.add(ActionWrapper.InsertActionWrapper(action))
                }
                is Move -> {
                    val parentElement = (action.parent as PyPsiGumTree).rootElement!!
                    (action.parent as PyPsiGumTree).rootVertex = psiToPatternVertex[parentElement]
                    (action.node as PyPsiGumTree).rootVertex = psiToPatternVertex[element]
                    actionsWrappers.add(ActionWrapper.MoveActionWrapper(action))
                }
            }
        }
        return Json.encodeToString(actionsWrappers)
    }
}