from typing import Generator, Any, Optional

import networkx as nx
from networkx.algorithms import isomorphism


class SubgraphSeeker:
    def __init__(self, target_method_graph: nx.MultiDiGraph):
        self.target_method_graph = target_method_graph

    def find_isomorphic_subgraphs(self, pattern_graph: nx.MultiDiGraph) -> Optional[Generator[dict, Any, None]]:
        pattern_graph_before = pattern_graph.subgraph(
            [node for node, attrs in pattern_graph.nodes.items() if attrs['color'] == 'red2'])
        matcher = isomorphism.MultiDiGraphMatcher(G1=self.target_method_graph,
                                                  G2=pattern_graph_before,
                                                  node_match=self._are_nodes_equal)
        if not matcher.subgraph_is_isomorphic():
            return None
        return matcher.subgraph_isomorphisms_iter()

    @staticmethod
    def _are_nodes_equal(target_node, pattern_node):
        if ('label' not in target_node
                or 'original_label' not in target_node
                or 'label' not in pattern_node
                or 'original_label' not in pattern_node):
            return False
        if pattern_node['label'].startswith('var') and target_node['label'].startswith('var'):
            lcs = pattern_node.get('longest_common_var_name_suffix', None)
            if lcs is None:
                raise KeyError('Use NxGraphCreator.create_from_pattern_fragments() method')
            return lcs is not None and target_node['original_label'].endswith(lcs) or lcs is None
        return (pattern_node['label'] == target_node['label']
                and pattern_node['original_label'] == target_node['original_label'])
