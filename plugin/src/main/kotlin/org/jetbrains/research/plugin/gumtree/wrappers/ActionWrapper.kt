package org.jetbrains.research.plugin.gumtree.wrappers

import com.github.gumtreediff.actions.model.Delete
import com.github.gumtreediff.actions.model.Insert
import com.github.gumtreediff.actions.model.Move
import com.github.gumtreediff.actions.model.Update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.research.plugin.PatternGraph
import org.jetbrains.research.plugin.gumtree.PyPsiGumTree
import org.jetbrains.research.plugin.jgrapht.findVertexById

@Serializable
sealed class ActionWrapper {

    @Serializable
    @SerialName("Delete")
    class DeleteActionWrapper : ActionWrapper {
        private var targetVertexWrapper: PyPsiGumTreeWrapper

        constructor(action: Delete) : super() {
            this.targetVertexWrapper = PyPsiGumTreeWrapper(action.node as PyPsiGumTree)
        }

        fun reconstructAction(correspondingGraph: PatternGraph): com.github.gumtreediff.actions.model.Delete {
            targetVertexWrapper.rootVertex = correspondingGraph.findVertexById(targetVertexWrapper.rootVertexId!!)
            return Delete(targetVertexWrapper.getNode())
        }
    }

    @Serializable
    @SerialName("Update")
    class UpdateActionWrapper : ActionWrapper {
        private var targetVertexWrapper: PyPsiGumTreeWrapper
        private var value: String

        constructor(action: Update) : super() {
            this.targetVertexWrapper = PyPsiGumTreeWrapper(action.node as PyPsiGumTree)
            this.value = action.value
        }

        fun reconstructAction(correspondingGraph: PatternGraph): Update {
            targetVertexWrapper.rootVertex = correspondingGraph.findVertexById(targetVertexWrapper.rootVertexId!!)
            return Update(targetVertexWrapper.getNode(), value)
        }
    }

    @Serializable
    @SerialName("Insert")
    class InsertActionWrapper : ActionWrapper {
        private var targetVertexWrapper: PyPsiGumTreeWrapper
        private var parentVertexWrapper: PyPsiGumTreeWrapper?
        private var position: Int

        constructor(action: Insert) : super() {
            this.targetVertexWrapper = PyPsiGumTreeWrapper(action.node as PyPsiGumTree)
            this.parentVertexWrapper = PyPsiGumTreeWrapper(action.parent as PyPsiGumTree)
            this.position = action.position
        }

        fun reconstructAction(correspondingGraph: PatternGraph): Insert {
            targetVertexWrapper.rootVertex = correspondingGraph.findVertexById(targetVertexWrapper.rootVertexId!!)
            parentVertexWrapper?.rootVertex = correspondingGraph.findVertexById(parentVertexWrapper?.rootVertexId!!)
            return Insert(targetVertexWrapper.getNode(), parentVertexWrapper?.getNode(), position)
        }
    }

    @Serializable
    @SerialName("Move")
    class MoveActionWrapper : ActionWrapper {
        private var targetVertexWrapper: PyPsiGumTreeWrapper
        private var parentVertexWrapper: PyPsiGumTreeWrapper?
        private var position: Int

        constructor(action: Move) : super() {
            this.targetVertexWrapper = PyPsiGumTreeWrapper(action.node as PyPsiGumTree)
            this.parentVertexWrapper = PyPsiGumTreeWrapper(action.parent as PyPsiGumTree)
            this.position = action.position
        }

        fun reconstructAction(correspondingGraph: PatternGraph): Move {
            targetVertexWrapper.rootVertex = correspondingGraph.findVertexById(targetVertexWrapper.rootVertexId!!)
            parentVertexWrapper?.rootVertex = correspondingGraph.findVertexById(parentVertexWrapper?.rootVertexId!!)
            return Move(targetVertexWrapper.getNode(), parentVertexWrapper?.getNode(), position)
        }
    }
}