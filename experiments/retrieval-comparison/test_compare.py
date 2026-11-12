import math
import unittest
from compare import query_metrics, rankers


class RetrievalExperimentTest(unittest.TestCase):
    def test_ranking_metrics_with_two_graded_documents(self):
        result = query_metrics(['irrelevant', 'a', 'b'], {'a': 2, 'b': 1}, 3)
        self.assertEqual(result['recall'], 1)
        self.assertEqual(result['mrr'], .5)
        dcg = 3 / math.log2(3) + 1 / math.log2(4)
        ideal = 3 + 1 / math.log2(3)
        self.assertAlmostEqual(result['ndcg'], dcg / ideal)
        self.assertEqual(query_metrics(['irrelevant'], {'a': 1}, 1), {'recall': 0, 'mrr': 0.0, 'ndcg': 0.0})

    def test_both_retrievers_exclude_zero_matches_and_break_ties_by_id(self):
        docs = [{'id': 'b', 'title': '', 'text': 'apple'}, {'id': 'a', 'title': '', 'text': 'apple'}, {'id': 'c', 'title': '', 'text': 'orange'}]
        search = rankers(docs)
        for method in ('tfidf', 'bm25'):
            self.assertEqual(search('apple', method), ['a', 'b'])
            self.assertEqual(search('missing', method), [])


if __name__ == '__main__':
    unittest.main()
