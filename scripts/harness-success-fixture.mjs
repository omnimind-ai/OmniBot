// Deterministic SDK-compatible success responses, no model inference.
export function respondHarnessSuccess(req, res, body, {completedTextOnly = false, partialText = false, reply = 'OK'} = {}) {
  const path = new URL(req.url, 'http://fixture').pathname;
  const model = body.model;
  if (path.endsWith('/messages')) {
    const message = {id:'msg_fixture',type:'message',role:'assistant',model,content:[{type:'text',text:reply}],stop_reason:'end_turn',stop_sequence:null,usage:{input_tokens:5,output_tokens:1}};
    if (!body.stream) {res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(message));return;}
    res.writeHead(200,{'content-type':'text/event-stream'});
    const event=(type,data)=>res.write(`event: ${type}\ndata: ${JSON.stringify({type,...data})}\n\n`);
    event('message_start',{message:{...message,content:[],stop_reason:null,usage:{input_tokens:5,output_tokens:0}}});
    event('content_block_start',{index:0,content_block:{type:'text',text:''}});
    event('content_block_delta',{index:0,delta:{type:'text_delta',text:reply}});
    event('content_block_stop',{index:0});
    event('message_delta',{delta:{stop_reason:'end_turn',stop_sequence:null},usage:{output_tokens:1}});
    event('message_stop',{});res.end();return;
  }
  if (path.endsWith('/responses')) {
    const part={type:'output_text',text:'OK',annotations:[],logprobs:[]};
    const item={id:'msg_fixture',type:'message',role:'assistant',status:'completed',content:[part]};
    const response={id:'resp_fixture',object:'response',created_at:1,model,status:'completed',output:[item],usage:{input_tokens:5,output_tokens:1,total_tokens:6}};
    if (!body.stream) {res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(response));return;}
    res.writeHead(200,{'content-type':'text/event-stream'});let sequence=0;
    const event=(type,data)=>res.write(`event: ${type}\ndata: ${JSON.stringify({type,sequence_number:sequence++,...data})}\n\n`);
    event('response.created',{response:{...response,status:'in_progress',output:[]}});
    event('response.output_item.added',{output_index:0,item:{...item,status:'in_progress',content:[]}});
    event('response.content_part.added',{item_id:item.id,output_index:0,content_index:0,part:{...part,text:''}});
    if (!completedTextOnly) event('response.output_text.delta',{item_id:item.id,output_index:0,content_index:0,delta:partialText ? 'O' : 'OK'});
    event('response.output_text.done',{item_id:item.id,output_index:0,content_index:0,text:'OK'});
    event('response.content_part.done',{item_id:item.id,output_index:0,content_index:0,part});
    event('response.output_item.done',{output_index:0,item});
    event('response.completed',{response});res.end();return;
  }
  const usage={prompt_tokens:5,completion_tokens:1,total_tokens:6};
  if (!body.stream) {res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({id:'chat_fixture',object:'chat.completion',created:1,model,choices:[{index:0,message:{role:'assistant',content:'OK'},finish_reason:'stop'}],usage}));return;}
  res.writeHead(200,{'content-type':'text/event-stream'});
  for (const [delta,finish] of [[{role:'assistant',content:'OK'},null],[{},'stop']]) {
    res.write(`data: ${JSON.stringify({id:'chat_fixture',object:'chat.completion.chunk',created:1,model,choices:[{index:0,delta,finish_reason:finish}],usage})}\n\n`);
  }
  res.end('data: [DONE]\n\n');
}
