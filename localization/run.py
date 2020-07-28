import ast
import pickle
from datetime import datetime
from typing import List, Dict

import asttokens
import networkx as nx
from tqdm import tqdm

import pyflowgraph
from common.utils import get_maximal_subtree
from localization.subgraphs import SubgraphSeeker
from localization.subtrees import SubtreeSeeker
from common.models import AdjacencyList
from preprocessing.loaders import NxGraphCreator


def locate_pattern_by_subtree(patterns_subtrees_paths: List[str], target_method_path):
    # Create and load corresponding patterns' subtrees
    with open(target_method_path, 'rb') as file:
        target_method_src = file.read()
    target_method_ast = ast.parse(target_method_src, mode='exec')
    target_method_tokenized_ast = asttokens.ASTTokens(target_method_src, tree=target_method_ast)
    seeker = SubtreeSeeker(target_method_tokenized_ast)
    max_subtree_by_path = {}
    print(f'Start loading subtrees')
    for pattern_subtrees_path in patterns_subtrees_paths:
        with open(pattern_subtrees_path, 'rb') as file:
            subtrees: List[AdjacencyList] = pickle.load(file)
        max_subtree_by_path[pattern_subtrees_path] = get_maximal_subtree(subtrees)
    print(f'All {len(patterns_subtrees_paths)} subtrees are loaded, maximal subtree in the each case was extracted\n')

    # Locate subtrees
    max_size = 0
    result_mappings = {}
    start_time = datetime.now()
    for path, subtree in max_subtree_by_path.items():
        found = seeker.find_isomorphic_subtree(subtree)
        if found is not None:
            subtree_size = len(subtree.nodes)
            if subtree_size >= max_size:
                max_size = subtree_size
                result_mappings.setdefault(max_size, []).append((found, path))
    end_time = datetime.now()

    # Print results
    print(f'Total time for subtrees search: {end_time - start_time}')
    if result_mappings.get(max_size, None) is not None:
        print(f'There are {len(result_mappings[max_size])} suitable patterns, with maximal subtree size {max_size}:')
        for mapping, path in result_mappings[max_size]:
            print(f"Path to pattern's subtrees: {path}")
            print(f'Mapping: {mapping}')
    else:
        print('Nothing suitable found')
    print()


def locate_pattern_by_subgraph_from_source(pattern_graph_by_path: Dict[str, nx.MultiDiGraph],
                                           target_method_graph: nx.MultiDiGraph):
    seeker = SubgraphSeeker(target_method_graph)
    max_size = 0
    result_mappings = {}
    start_time = datetime.now()
    for path, pattern_graph in pattern_graph_by_path.items():
        found = seeker.find_isomorphic_subgraphs(pattern_graph)
        if found is not None:
            pattern_size = len(pattern_graph.nodes)
            if pattern_size > max_size:
                max_size = pattern_size
                result_mappings.setdefault(max_size, []).append((found, path))
    end_time = datetime.now()
    print(f'Total time for pattern search: {end_time - start_time}')
    if result_mappings.get(max_size, None) is not None:
        print(f'There are {len(result_mappings[max_size])} suitable patterns, with maximal pattern size {max_size}:')
        for subgraph_generator, path in result_mappings[max_size]:
            print(f"Path to pattern's graph: {path}")
            print(f'Subgraph node ids mapping: {next(subgraph_generator)}')
    else:
        print('Nothing suitable found')
    print()


def locate_pattern_by_subgraph_from_files(patterns_graphs_paths: List[str], target_method_path):
    pfg = pyflowgraph.build_from_file(target_method_path)
    target_method_graph = NxGraphCreator.create_from_pyflowgraph(pfg)
    pattern_graph_by_path = {}
    print(f'Start loading patterns')
    for pattern_graph_path in tqdm(patterns_graphs_paths):
        pattern_graph_by_path[pattern_graph_path] = NxGraphCreator.create_from_dot_file(pattern_graph_path)
    print(f'All {len(patterns_graphs_paths)} patterns are loaded\n')
    locate_pattern_by_subgraph_from_source(pattern_graph_by_path, target_method_graph)
