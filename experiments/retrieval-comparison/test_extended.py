import json
import tempfile
import unittest
from pathlib import Path
from extended import load_beir, make_lsa, rrf, evaluate


class ExtendedExperimentTest(unittest.TestCase):
    def test_rrf_combines_rankings_and_breaks_ties(self):
        self.assertEqual(rrf(['a', 'b'], ['b', 'c']), ['b', 'a', 'c'])
        self.assertEqual(rrf([], []), [])

    def test_beir_loader_retains_full_corpus_and_only_labelled_questions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'qrels').mkdir()
            (root/'corpus.jsonl').write_text('\n'.join(json.dumps(x) for x in [{'_id':'a','text':'alpha'},{'_id':'b','text':'beta'},{'_id':'c','text':'other'}]))
            (root/'queries.jsonl').write_text('\n'.join(json.dumps(x) for x in [{'_id':'1','text':'alpha'},{'_id':'2','text':'ignored'}]))
            (root/'qrels/test.tsv').write_text('query-id\tcorpus-id\tscore\n1\ta\t1\n')
            docs, queries = load_beir(root)
            self.assertEqual(len(docs), 3)
            self.assertEqual(queries[0]['relevant'], {'a':1})
            self.assertEqual(len(queries), 1)
            (root/'qrels/test.tsv').write_text('query-id\tcorpus-id\tscore\n1\tunknown\t1\n')
            with self.assertRaises(ValueError):
                load_beir(root)

    def test_lsa_no_terms_returns_no_results(self):
        docs = [{'id':'a','title':'','text':'apple fruit'}, {'id':'b','title':'','text':'orange fruit'}, {'id':'c','title':'','text':'server network'}]
        search, dims = make_lsa(docs)
        self.assertEqual(dims, 2)
        self.assertEqual(search('absent',), [])
        self.assertEqual(search('apple')[0], 'a')

    def test_result_and_timing_counts(self):
        docs = [{'id':'a','title':'','text':'apple fruit'}, {'id':'b','title':'','text':'orange fruit'}, {'id':'c','title':'','text':'server network'}]
        q = [{'id':'q', 'query':'apple', 'relevant':{'a':1}}]
        result, timing = evaluate(docs, q, 2)
        self.assertEqual(set(result['runs']), {'tfidf','bm25','lsa','bm25_lsa_rrf'})
        self.assertEqual(timing['samples_per_method'], 2)
        self.assertEqual(result['runs']['bm25']['summary']['recall@5'],1)
        self.assertGreaterEqual(timing['runs']['bm25']['p95_ms'],0)


if __name__ == '__main__':
    unittest.main()
