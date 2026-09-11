import importlib.util, json, sqlite3, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('outcome',Path(__file__).with_name('assert-agent-turn-outcome.py'))
outcome=importlib.util.module_from_spec(spec); spec.loader.exec_module(outcome)

class TurnOutcomeTest(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE agent_conversation_entries(id INTEGER PRIMARY KEY,conversationId INTEGER,entryType TEXT,payloadJson TEXT)')
    def add(self,kind,payload,conversation=1):
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId,entryType,payloadJson) VALUES(?,?,?)',(conversation,kind,json.dumps(payload)))
    def user(self,marker): self.add('user_message',{'content':{'text':'Reply '+marker}})
    def error(self): self.add('tool_event',{'toolName':'agent.status','status':'error','streamMeta':{'sessionId':'s','turnId':'t','stopReason':'error'}})
    def test_codex_command_rejects_startup_failure_and_missing_output(self):
        marker='OOB_LIVE_CODEX_EXIT_1';self.user(marker)
        self.add('assistant_message',{'content':{'text':marker+'_DONE'},'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'end_turn'}})
        for code,text,success,valid in [(182,'',False,False),(7,'',False,False),(7,marker+'_STDOUT '+marker+'_STDERR',True,False),(7,marker+'_STDOUT '+marker+'_STDERR',False,True)]:
            with self.subTest(code=code,text=text,success=success):
                self.db.execute("DELETE FROM agent_conversation_entries WHERE entryType='tool_event'")
                tool={'success':success,'rawResultJson':json.dumps({'type':'commandExecution','rawOutput':{'exit_code':code,'formatted_output':text}}),'streamMeta':{'sessionId':'s','turnId':'t'}}
                self.add('tool_event',tool)
                if valid:
                    self.assertTrue(outcome.verify(self.db,marker,'done')['passed'])
                    self.add('tool_event',tool)
                with self.assertRaises(AssertionError): outcome.verify(self.db,marker,'done')
    def test_codex_detail_requires_actual_persisted_exit_explanation(self):
        marker='OOB_LIVE_CODEX_DETAIL_1';self.user(marker)
        self.add('assistant_message',{'content':{'text':marker+'_DONE'},'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'end_turn'}})
        for summary,valid in [('',False),('Command exited with code 7',False),('Command exited with code 182',True)]:
            self.db.execute("DELETE FROM agent_conversation_entries WHERE entryType='tool_event'")
            self.add('tool_event',{'status':'error','success':False,'summary':summary,'rawResultJson':json.dumps({'type':'commandExecution','rawOutput':{'exit_code':182}}),'streamMeta':{'sessionId':'s','turnId':'t'}})
            if valid:self.assertTrue(outcome.verify(self.db,marker,'done')['passed'])
            else:
                with self.assertRaises(AssertionError):outcome.verify(self.db,marker,'done')
    def test_terminal_recovery_requires_real_successful_tool(self):
        marker='OOB_LIVE_TERMINAL_RECOVERY_1'
        self.user(marker)
        self.add('assistant_message',{'content':{'text':marker+'_DONE'},'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'end_turn'}})
        with self.assertRaises(AssertionError):outcome.verify(self.db,marker,'done')
        tool={'toolName':'terminal_execute','success':True,'rawResultJson':json.dumps({'result':{'stdout':marker+'_DONE\n'}}),'streamMeta':{'sessionId':'s','turnId':'t'}}
        self.add('tool_event',tool)
        self.assertTrue(outcome.verify(self.db,marker,'done')['passed'])
        self.add('tool_event',tool)
        with self.assertRaises(AssertionError):outcome.verify(self.db,marker,'done')
    def test_terminal_completion_can_end_on_tool_without_assistant_prose(self):
        marker='OOB_LIVE_TERMINAL_RECOVERY_2';self.user(marker)
        tool={'toolName':'terminal_execute','success':True,'rawResultJson':json.dumps({'result':{'stdout':marker+'_DONE\n'}}),'streamMeta':{'sessionId':'s','turnId':'t'}}
        self.add('tool_event',tool)
        with self.assertRaises(AssertionError):outcome.verify(self.db,marker,'done')
        tool['streamMeta']['stopReason']='end_turn'
        self.db.execute("DELETE FROM agent_conversation_entries WHERE entryType='tool_event'")
        self.add('tool_event',tool)
        self.assertTrue(outcome.verify(self.db,marker,'done')['passed'])
        tool['rawResultJson']=json.dumps({'result':{'stdout':'unrelated'}})
        self.db.execute("DELETE FROM agent_conversation_entries WHERE entryType='tool_event'")
        self.add('tool_event',tool)
        with self.assertRaises(AssertionError):outcome.verify(self.db,marker,'done')
    def test_tool_only_turn_can_have_official_cancellation(self):
        self.user('OOB_NEW')
        self.add('tool_event',{'toolName':'terminal_session_exec','streamMeta':{'sessionId':'s','turnId':'t','stopReason':'cancelled'}})
        self.assertTrue(outcome.verify(self.db,'OOB_NEW','cancelled')['passed'])
    def test_tool_text_without_terminal_metadata_cannot_claim_cancellation(self):
        self.user('OOB_NEW')
        self.add('tool_event',{'summary':'cancelled','streamMeta':{'sessionId':'s','turnId':'t'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','cancelled')

    def test_inband_text_error_must_preserve_partial_output(self):
        marker='OOB_FAILURE_STREAMERROR_1'
        self.user(marker);self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,marker,'error')
        self.add('assistant_message',{'content':{'text':marker+'_PARTIAL'},'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'error'}})
        self.assertTrue(outcome.verify(self.db,marker,'error')['passed'])
    def test_inband_tool_error_must_not_execute_buffered_tool(self):
        marker='OOB_FAILURE_STREAMTOOL_1'
        self.user(marker);self.error()
        self.add('tool_event',{'toolName':'agent.file','status':'pending','success':False,'streamMeta':{'sessionId':'s','turnId':'t'}})
        self.assertTrue(outcome.verify(self.db,marker,'error')['passed'])
        self.add('tool_event',{'toolName':'file_write','success':True,'streamMeta':{'sessionId':'s','turnId':'t'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,marker,'error')

    def test_mcp_denial_requires_one_failed_tool_and_http_category(self):
        marker='OOB_LIVE_MCP_DENIED_1'
        self.user(marker)
        self.add('assistant_message',{'content':{'text':marker+'_DONE'},'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'end_turn'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,marker,'done')
        tool={'toolName':'mcp__fixture__lifecycle_denied__hash','success':False,'summary':'HTTP 401: Unauthorized','streamMeta':{'sessionId':'s','turnId':'t'}}
        self.add('tool_event',tool)
        self.assertTrue(outcome.verify(self.db,marker,'done')['passed'])
        self.add('tool_event',tool)
        with self.assertRaises(AssertionError): outcome.verify(self.db,marker,'done')

    def test_earlier_failure_cannot_satisfy_new_turn(self):
        self.user('OOB_OLD');self.error();self.user('OOB_NEW')
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error')
    def test_later_failure_cannot_satisfy_earlier_turn(self):
        self.user('OOB_OLD');self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_OLD','error')
    def test_duplicate_user_admission_is_rejected(self):
        self.user('OOB_NEW');self.error();self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error')
    def test_current_failure_is_accepted(self):
        self.user('OOB_NEW');self.error()
        self.assertTrue(outcome.verify(self.db,'OOB_NEW','error')['passed'])
    def test_wrong_error_category_is_rejected(self):
        self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error','quota')
    def test_unrelated_turn_identity_is_rejected(self):
        self.user('OOB_NEW');self.error()
        self.add('assistant_message',{'streamMeta':{'sessionId':'other','turnId':'t'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error')
    def test_failure_cannot_be_reported_as_recovery(self):
        self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','recovered')

    def test_cancelled_requires_official_terminal_reason(self):
        self.user('OOB_NEW')
        self.add('assistant_message',{'content':{'text':'waiting'},'streamMeta':{'sessionId':'s','turnId':'t'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','cancelled')
    def test_cancelled_is_accepted_without_becoming_failure(self):
        self.user('OOB_NEW')
        self.add('assistant_message',{'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'cancelled'}})
        self.assertTrue(outcome.verify(self.db,'OOB_NEW','cancelled')['passed'])
    def test_failure_or_success_cannot_pass_as_cancelled(self):
        self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','cancelled')
    def test_cancelled_but_loading_is_rejected(self):
        self.user('OOB_NEW')
        self.add('assistant_message',{'isLoading':True,'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'cancelled'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','cancelled')

    def test_live_scenario_requires_canonical_completion_after_freeform_reply(self):
        self.add('user_message',{'content':{'text':'Run the file checks. End your final reply with OOB_LIVE_FILES_1_DONE.'}})
        self.add('assistant_message',{'content':{'text':'Checks completed.\n**OOB_LIVE_FILES_1_DONE**'},'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'end_turn'}})
        self.assertTrue(outcome.verify(self.db,'OOB_LIVE_FILES_1','done')['passed'])

    def test_live_reply_without_prompt_response_is_not_done(self):
        self.add('user_message',{'content':{'text':'Run checks. End your final reply with OOB_LIVE_FILES_1_DONE.'}})
        self.add('assistant_message',{'content':{'text':'OOB_LIVE_FILES_1_DONE'},'streamMeta':{'sessionId':'s','turnId':'t'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_LIVE_FILES_1','done')

    def test_quoted_live_marker_does_not_match_user_admission(self):
        self.add('user_message',{'content':{'text':'Discuss OOB_LIVE_FILES_1'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_LIVE_FILES_1','done')

    def recovery(self,marker,diagnostic,tool='file_read'):
        self.user(marker)
        self.add('tool_event',{'toolName':tool,'success':False,'summary':diagnostic,'streamMeta':{'sessionId':'s','turnId':'t'}})
        self.add('assistant_message',{'content':{'text':marker+'_RECOVERED'},'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'end_turn'}})
    def test_permission_failure_cannot_satisfy_missing_file(self):
        self.recovery('OOB_FAILURE_MISSING_1','Permission denied')
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_FAILURE_MISSING_1','recovered')
    def test_actual_missing_path_is_accepted(self):
        self.recovery('OOB_FAILURE_MISSING_1','File does not exist: /workspace/OOB_FAILURE_MISSING_1-missing.txt')
        self.assertTrue(outcome.verify(self.db,'OOB_FAILURE_MISSING_1','recovered')['passed'])
    def test_unknown_tool_must_identify_the_failed_capability(self):
        self.recovery('OOB_FAILURE_UNKNOWN_1','File does not exist')
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_FAILURE_UNKNOWN_1','recovered')
    def test_bad_arguments_must_reach_argument_parsing(self):
        self.recovery('OOB_FAILURE_ARGS_1','Permission denied')
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_FAILURE_ARGS_1','recovered')


    def permission(self,status='pending',request_id='request-1'):
        self.add('ui_card',{'streamMeta':{'sessionId':'s','turnId':'t'},'content':{'cardData':{'type':'agent_request','requestKind':'approval','status':status,'requestId':request_id}}})
    def test_current_pending_permission_is_required(self):
        self.user('OOB_NEW');self.permission()
        self.assertTrue(outcome.verify(self.db,'OOB_NEW','permission-pending')['passed'])
    def test_historical_permission_cannot_satisfy_current_wait(self):
        self.user('OOB_OLD');self.permission();self.user('OOB_NEW')
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','permission-pending')
    def test_resolved_or_unaddressable_permission_is_not_pending(self):
        for status,request in [('declined','request-1'),('pending',None)]:
            self.db.execute('DELETE FROM agent_conversation_entries')
            self.user('OOB_NEW');self.permission(status,request)
            with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','permission-pending')

    def test_cancelled_turn_cannot_keep_pending_permission(self):
        self.user('OOB_NEW');self.permission()
        self.add('assistant_message',{'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'cancelled'}})
        with self.assertRaisesRegex(AssertionError,'actionable request'):
            outcome.verify(self.db,'OOB_NEW','cancelled')

if __name__=='__main__': unittest.main()
