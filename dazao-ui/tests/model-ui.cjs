// Frontend-only provider fixtures. No real model call or production data is used.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const baseURL=process.env.UI_QA_URL || 'http://127.0.0.1:5199';
if (!['127.0.0.1','localhost'].includes(new URL(baseURL).hostname)) throw new Error('Use an isolated local backend only.');
const output=process.env.UI_QA_OUTPUT || path.resolve(__dirname, '../../.runtime/ui-browser');fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH || undefined,headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const checks=[];const ok=n=>{checks.push(n);console.log('PASS',n)};
 const shot=async n=>page.screenshot({path:path.join(output,n+'.png')});
 await page.route('**/api/auth/session',route=>route.abort('failed'));
 await page.goto(baseURL);await page.getByRole('heading',{name:'暂时无法连接工作室'}).waitFor();await shot('连接失败-可重试');
 await page.unroute('**/api/auth/session');await page.getByRole('button',{name:'重新连接',exact:true}).click();
 await page.getByRole('heading',{name:'从一个灵感开始'}).waitFor();ok('connection failure and explicit retry recovery');
 await page.route('**/api/auth/session',async route=>{await new Promise(r=>setTimeout(r,700));await route.continue()});
 await page.goto(baseURL);await page.getByRole('region',{name:'正在加载工作室'}).waitFor();await shot('加载状态-桌面');
 await page.getByRole('heading',{name:'从一个灵感开始'}).waitFor();await page.unroute('**/api/auth/session');
 ok('initial loading state');
 await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('button',{name:'还没有账号？创建账号'}).click();
 await page.locator('#username').fill('fixture_'+Date.now());await page.locator('#password').fill('fixture-test-password');
 await page.getByRole('button',{name:'注册并登录',exact:true}).click();await page.waitForFunction(()=>!document.querySelector('dialog').open);
 const definition={name:'灯下',description:'在深夜书店修复旧书的猫妖，为晚归的人留一盏灯。',personality:'外冷内热，习惯用小小的行动表达关心。',firstMessage:'书还没有修好，先坐一会儿吧。今天，想听哪个故事？',scenario:'窗外落着细雨的深夜书店',speechStyle:'短句，带一点冷幽默',messageExample:'',appearance:'灰色长衣',sourceType:'original',work:'',canonVersion:''};
 await page.locator('#brief').fill('一位在深夜书店修复旧书的猫妖');
 let generationCalls=0;
 await page.route('**/api/characters/generate',async route=>{
  generationCalls++;await new Promise(r=>setTimeout(r,600));
  await route.fulfill({json:definition});
 });
 await page.getByRole('button',{name:'生成角色草稿',exact:true}).evaluate(b=>{b.click();b.click()});
 await page.getByText('正在生成，请稍候…',{exact:true}).waitFor();
 assert.equal(await page.getByRole('button',{name:'正在生成…',exact:true}).isDisabled(),true);
 await page.getByText('草稿已生成。检查设定后保存，也可以锁定满意的字段继续打磨。',{exact:true}).waitFor();
 assert.equal(generationCalls,1);assert.equal(await page.locator('#field-name').inputValue(),'灯下');
 await page.getByRole('button',{name:'锁定角色名称',exact:true}).click();await page.getByRole('checkbox',{name:'选择重生成性格与习惯'}).check();
 await page.unroute('**/api/characters/generate');
 await page.route('**/api/characters/generate',async route=>{
  const body=route.request().postDataJSON();assert.deepEqual(body.lockedFields,['name']);assert.deepEqual(body.selectedFields,['personality']);
  await route.fulfill({json:{...definition,personality:'更温柔一些，但依然不多话。'}});
 });
 await page.getByRole('button',{name:'重生成所选字段',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#field-personality').value==='更温柔一些，但依然不多话。');
 assert.equal(await page.locator('#field-name').inputValue(),'灯下');
 ok('generation busy state, duplicate suppression, selected/locked payload and draft rendering');
 await page.getByRole('button',{name:'保存角色',exact:true}).click();await page.getByText('所有更改已保存',{exact:true}).waitFor();
 await page.evaluate(()=>scrollTo(0,0));await shot('角色编辑-完成设定');
 await page.getByRole('button',{name:'开始试聊',exact:true}).click();await page.locator('#message').waitFor();
 const storedTurns=[];let messageCalls=0;let lastInput='';
 await page.route('**/api/conversations/*',async route=>{
  if(route.request().method()!=='GET'){await route.continue();return}
  const response=await route.fetch();const data=await response.json();await route.fulfill({json:{...data,turns:storedTurns}});
 });
 await page.route('**/api/conversations/*/messages',async route=>{
  messageCalls++;lastInput=route.request().postDataJSON().content;await new Promise(r=>setTimeout(r,600));
  const reply='当然。雨停之前，我给你讲一个关于旧书和星星的故事。';
  storedTurns.push({id:'ui-fixture-'+messageCalls,userContent:lastInput,assistantContent:reply,status:'DONE'});
  await route.fulfill({contentType:'text/event-stream',body:'event:delta\ndata:'+JSON.stringify({text:'当然。'})+'\n\nevent:done\ndata:'+JSON.stringify({turnId:'ui-fixture-1',content:reply})+'\n\n'});
 });
 await page.locator('#message').fill('你好，今晚有什么故事？');
 await page.getByRole('button',{name:'发送',exact:true}).evaluate(b=>{b.click();b.click()});
 await page.getByText('角色正在回复…',{exact:true}).waitFor();assert.equal(await page.locator('#message').isDisabled(),true);
 await page.waitForFunction(()=>document.querySelector('#message').value==='');assert.equal(messageCalls,1);
 await page.getByText('当然。雨停之前，我给你讲一个关于旧书和星星的故事。',{exact:true}).waitFor();
 await page.getByRole('button',{name:'关闭通知'}).click().catch(()=>{});await shot('会话成功夹具-桌面');
 await page.setViewportSize({width:390,height:844});await page.evaluate(()=>scrollTo(0,document.documentElement.scrollHeight));await shot('会话成功夹具-手机');
 ok('SSE success rendering, reply busy state, single submission and input clearing (fixture)');
 await page.unroute('**/api/conversations/*/messages');
 await page.route('**/api/conversations/*/messages',async route=>{
  const input=route.request().postDataJSON().content;
  storedTurns.push({id:'ui-failed',userContent:input,assistantContent:'未完成的半句话',status:'FAILED'});
  await route.fulfill({contentType:'text/event-stream',body:'event:delta\ndata:{"text":"未完成的半句话"}\n\nevent:error\ndata:{"message":"回复未完成，输入已保留，可重试"}\n\n'});
 });
 await page.locator('#message').fill('继续讲下去吧。');await page.getByRole('button',{name:'发送',exact:true}).click();
 await page.getByText('失败记录不进入后续对话，可以重新发送',{exact:true}).waitFor();
 assert.equal(await page.locator('#message').inputValue(),'继续讲下去吧。');
 await shot('会话失败夹具-手机');ok('SSE partial/error rendering and retry input preservation (fixture)');
 // Verify actual confirmation commit path, not just cancellation.
 await page.getByRole('button',{name:'删除会话',exact:true}).click();
 await page.getByRole('dialog').getByRole('button',{name:'删除会话',exact:true}).evaluate(b=>{b.click();b.click()});
 await page.getByRole('heading',{name:'故事，从一句你好开始'}).waitFor();
 assert.equal(await page.locator('.session-button').count(),0);ok('confirmed deletion removes actual conversation exactly once');
 assert.deepEqual(errors,[]);
 fs.writeFileSync(path.join(output,'模型交互夹具验证.json'),JSON.stringify({baseURL,fixtures:'Browser network fixtures only for generation, SSE and turn readback. Registration, save, conversation creation/deletion use real local backend.',realModelCalls:0,checks,errors},null,2));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
