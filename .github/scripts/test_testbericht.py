import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name('testbericht.py')
spec = importlib.util.spec_from_file_location('testbericht', SCRIPT)
testbericht = importlib.util.module_from_spec(spec)
spec.loader.exec_module(testbericht)


class InstrumentierteAuswertungTest(unittest.TestCase):
    def test_direkter_test_zaehlt_und_bleibt_bei_retry_flaky(self):
        with tempfile.TemporaryDirectory() as basis:
            basis = Path(basis)
            kennung = 'com.example.lrmprotokoll.ui.PermissionTest#ohneBerechtigung'
            ziel = basis / 'permission' / 'TEST-PermissionTest.xml'
            testbericht.schreibe_einzeltest(str(ziel), kennung, False, 'zuerst rot')
            self.assertEqual({kennung: 'FAILED'}, testbericht.faelle(str(basis)))
            retries = basis / 'retries.tsv'
            retries.write_text(kennung + '\tPASSED\n', encoding='utf-8')
            _, flaky, failed, _, _ = testbericht.instrumentierte_auswertung(
                str(basis), str(retries), None)
            self.assertEqual([kennung], flaky)
            self.assertEqual([], failed)

    def test_eindeutige_methoden_und_policy(self):
        with tempfile.TemporaryDirectory() as basis:
            basis = Path(basis)
            xml = basis / 'connected' / 'TEST-example.xml'
            xml.parent.mkdir()
            xml.write_text('''<testsuite name="com.example.lrmprotokoll.ui.ExampleTest" tests="3" failures="2">
              <testcase classname="com.example.lrmprotokoll.ui.ExampleTest" name="flaky"><failure>zuerst rot</failure></testcase>
              <testcase classname="com.example.lrmprotokoll.ui.ExampleTest" name="failed"><failure>immer rot</failure></testcase>
              <testcase classname="com.example.lrmprotokoll.ui.ExampleTest" name="green"/>
            </testsuite>''', encoding='utf-8')
            retries = basis / 'retries.tsv'
            retries.write_text('com.example.lrmprotokoll.ui.ExampleTest#flaky\tPASSED\n'
                               'com.example.lrmprotokoll.ui.ExampleTest#failed\tFAILED\n', encoding='utf-8')
            quarantine = basis / 'quarantine.txt'
            quarantine.write_text('com.example.lrmprotokoll.ui.ExampleTest#failed | '
                                  'https://github.com/example/repo/issues/1 | 2020-01-01 | Untersuchung\n',
                                  encoding='utf-8')
            tests, flaky, failed, quarantined, warnings = testbericht.instrumentierte_auswertung(
                str(basis), str(retries), str(quarantine))
            self.assertEqual(3, len(tests))
            self.assertEqual(['com.example.lrmprotokoll.ui.ExampleTest#flaky'], flaky)
            self.assertEqual([], failed)
            self.assertEqual(['com.example.lrmprotokoll.ui.ExampleTest#failed'], quarantined)
            self.assertTrue(any('30 Tage' not in warning and 'Tage alt' in warning for warning in warnings))
            quarantine.write_text('', encoding='utf-8')
            _, flaky, failed, quarantined, warnings = testbericht.instrumentierte_auswertung(
                str(basis), str(retries), str(quarantine))
            self.assertEqual(['com.example.lrmprotokoll.ui.ExampleTest#flaky'], flaky)
            self.assertEqual(['com.example.lrmprotokoll.ui.ExampleTest#failed'], failed)
            self.assertEqual([], quarantined)
            self.assertEqual([], warnings)


if __name__ == '__main__':
    unittest.main()
