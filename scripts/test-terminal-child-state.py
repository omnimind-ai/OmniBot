#!/usr/bin/env python3
import runpy, unittest
from pathlib import Path
helpers=runpy.run_path(str(Path(__file__).with_name('assert-terminal-child-state.py')))
parse_stat=helpers['parse_stat']; validate=helpers['validate_child']; parse_pid=helpers['parse_pid']; validate_age=helpers['validate_age']

class ChildIdentityTest(unittest.TestCase):
    def setUp(self):
        self.stat='123 (sleep) '+' '.join(['S']+['0']*18+['42'])
    def test_remote_read_error_with_zero_adb_exit(self):
        decode=helpers['decode_read']
        self.assertIsNone(decode(0,b'cat: file: No such file or directory',b'',True))
        self.assertEqual(decode(0,b'123\n',b'',True),'123\n')
        with self.assertRaises(AssertionError):decode(0,b'cat: /proc/123/stat: Permission denied',b'',True)
    def test_unpublished_pid_waits(self):
        for value in [None, '', '\n']:
            self.assertIsNone(parse_pid(value))
        self.assertEqual(parse_pid('123\n'),123)
    def test_invalid_pid_rejected(self):
        for value in ['0', '1', '-2', '123 nope']:
            with self.assertRaises(AssertionError):parse_pid(value)
    def test_natural_expiry_cannot_satisfy_cancellation(self):
        self.assertEqual(validate_age('10000',101,100),1)
        for uptime in [99,220,273]:
            with self.assertRaises(AssertionError):validate_age('10000',uptime,100)
    def test_known_child(self):
        result=validate(self.stat,'sleep\0'+'173\0','Uid:\t10174\n','Uid:\t10174\n')
        self.assertEqual(result['startTime'],'42')
        self.assertEqual(result['uid'],10174)
    def test_other_owner_is_rejected(self):
        with self.assertRaises(AssertionError): validate(self.stat,'sleep\0'+'173\0','Uid:\t1000\n','Uid:\t10174\n')
    def test_other_command_is_rejected(self):
        for command in ['sleep\0'+'60\0','sh\0-c\0sleep 173\0','app_process\0'+'173\0']:
            with self.assertRaises(AssertionError):validate(self.stat,command,'Uid:\t10174\n','Uid:\t10174\n')
    def test_busybox_child(self):
        self.assertEqual(validate(self.stat,'/bin/busybox\0sleep\0'+'173\0','Uid:\t10174\n','Uid:\t10174\n')['command'][1],'sleep')
    def test_invalid_observation_is_rejected(self):
        for value in ['', 'permission denied', '123 (sleep) S']:
            with self.assertRaises(AssertionError):parse_stat(value)

if __name__=='__main__':unittest.main()
