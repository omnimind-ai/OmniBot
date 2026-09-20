"""Read-only content verification after the real two-image upload/restart journeys.

Usage: OOB_TEST_RELEASE_READ=1 python3 verify-chat-image-persistence.py SERIAL OUTPUT MARKER [MARKER...]
"""
import hashlib, json, os, re, shlex, subprocess, sys
from pathlib import Path
from agent_test_database import agent_database_snapshot

serial, output, *markers = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+', serial) and markers
results = []
with agent_database_snapshot(serial) as db:
    for marker in markers:
        assert re.fullmatch(r'OOB_LIVE_CHAT_IMAGE(?:_SECOND)?_\d+', marker)
        rows = db.execute("SELECT conversationId,payloadJson FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0", (marker,)).fetchall()
        assert len(rows)==1, 'User upload admission missing or duplicated'
        conversation, payload = rows[0]
        attachments = json.loads(payload)['content']['attachments']
        assert len(attachments)==1
        attachment = attachments[0]
        name = 'chat-upload-colors-second.png' if '_SECOND_' in marker else 'chat-upload-colors.png'
        assert attachment['name']==name and attachment['isImage'] is True
        assert attachment['mimeType']=='image/png'
        path = attachment['path']
        prefix = f'/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/attachments/conversation-{conversation}/'
        assert path.startswith(prefix) and re.fullmatch(r'[A-Za-z0-9_./-]+',path) and '..' not in path
        assert attachment['promptPath']==path.replace('/data/user/0/cn.com.omnimind.bot/workspace/', '/workspace/', 1)
        owner = [] if os.environ.get('OOB_TEST_RELEASE_READ')=='1' else ['run-as','cn.com.omnimind.bot']
        data = subprocess.check_output(['adb','-s',serial,'exec-out',shlex.join(owner+['cat',path])],timeout=30)
        expected = (Path(__file__).parent/'fixtures'/name).read_bytes()
        assert data==expected and attachment['size']==len(data), 'Persisted upload bytes differ from selected fixture'
        results.append({'marker':marker,'conversationId':conversation,'name':name,'sha256':hashlib.sha256(data).hexdigest(),'passed':True})
out=Path(output);out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps({'passed':True,'serial':serial,'uploads':results},indent=2))
print(json.dumps({'passed':True,'uploads':len(results)}))
