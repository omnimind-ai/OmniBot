#!/usr/bin/env python3
"""Verify the maintained 33-byte fixture in one init history/tool boundary."""
import argparse,json,subprocess,hashlib
from agent_test_database import agent_database_snapshot
p=argparse.ArgumentParser();p.add_argument('serial');p.add_argument('entry',type=int);a=p.parse_args()
expected=b'OOB_ATTACHMENT_ADMISSION_FIXTURE\n';name='oob-attachment-admission.txt'
with agent_database_snapshot(a.serial) as db:
    row=db.execute("SELECT conversationId,payloadJson FROM agent_conversation_entries WHERE id=? AND entryType='user_message'",(a.entry,)).fetchone();assert row
    user=json.loads(row[1]);assert user.get('content',{}).get('text')=='/init'
    refs=user['content']['attachments'];assert len(refs)==1 and refs[0]['name']==name
    end=db.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'",(row[0],a.entry)).fetchone()[0] or 2**63-1
    tools=[json.loads(r[0]) for r in db.execute("SELECT payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND id<? AND entryType='tool_event'",(row[0],a.entry,end))]
    reads=[]
    for tool in tools:
        if tool.get('toolName')!='file_read' or tool.get('success') is not True:continue
        args=tool.get('args',{});args=json.loads(args) if isinstance(args,str) else args
        path=args.get('path','')
        if path.startswith('/workspace/.omnibot/attachments/') and path.endswith(name):reads.append((tool,path))
    assert len(reads)==1,'Expected one successful model tool read of the staged fixture'
    tool,path=reads[0];assert expected.decode().strip() in tool.get('rawResultJson',''),'Tool did not return fixture bytes'
    for target in [refs[0]['path'],path.replace('/workspace/','workspace/',1)]:
        data=subprocess.check_output(['adb','-s',a.serial,'exec-out','run-as','cn.com.omnimind.bot','cat',target],timeout=30)
        assert data==expected,'Referenced file differs from fixture'
    print(json.dumps({'passed':True,'userEntryId':a.entry,'toolCallId':tool['toolCallId'],'sessionId':tool['sessionId'],'turnId':tool['turnId'],'bytes':len(expected),'sha256':hashlib.sha256(expected).hexdigest(),'historyReferenceAndWorkspaceCopyReadable':True,'modelToolReadReturnedFixture':True}))
