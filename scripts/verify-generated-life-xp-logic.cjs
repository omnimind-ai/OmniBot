// Independent regression for the generated LifeXPApp, not a replacement for UI acceptance.
// TZ=Asia/Shanghai (and America/Los_Angeles) node this-file.cjs /snapshot/app.js
const {readFileSync} = require('node:fs');
const {createHash} = require('node:crypto');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const sourcePath = process.argv[2];
assert(sourcePath && ['Asia/Shanghai', 'America/Los_Angeles'].includes(process.env.TZ));
const source = readFileSync(sourcePath, 'utf8');
console.log(JSON.stringify({sourcePath, sha256:createHash('sha256').update(source).digest('hex'),
  timezone:process.env.TZ, scope:'production methods with isolated clock/DOM fixtures; no real UI or database claim'}));

function load(DateType = Date, call = async () => { throw new Error('Unexpected tool call'); }) {
  const rows = [];
  const body = {innerHTML:'', appendChild(row) {rows.push(row.innerHTML);}};
  const context = vm.createContext({Date:DateType, setTimeout, clearTimeout,
    window:{omni:{tools:{call}}},
    document:{addEventListener(){}, getElementById(id) {
      assert.equal(id, 'history-body'); return body;
    }, createElement(tag) {assert.equal(tag, 'tr'); return {innerHTML:''};}}});
  // Export the real class from its script scope; no calculation is copied here.
  vm.runInContext(source+'\n;globalThis.ProductionApp = LifeXPApp;', context, {timeout:1000});
  return {App:context.ProductionApp, rows};
}

test('production constructor uses the local calendar day near midnight', () => {
  const hour = process.env.TZ === 'Asia/Shanghai' ? 0 : 23;
  const fixed = new Date(2026, 8, 19, hour, 30).getTime();
  class Clock extends Date {
    constructor(...args) {super(...(args.length ? args : [fixed]));}
    static now() {return fixed;}
  }
  const {App} = load(Clock);
  App.prototype.init = async () => {}; // Exclude startup IO; exercise the real constructor.
  assert.equal(new App().currentDate, '2026-09-19');
});

test('production date label preserves the stored local calendar day', () => {
  const {App} = load();
  const app = Object.create(App.prototype);
  const expected = new Date(2026,8,19).toLocaleDateString('zh-CN', {
    year:'numeric', month:'2-digit', day:'2-digit', weekday:'short',
  });
  assert.equal(app.formatDate('2026-09-19'), expected);
});

test('production refresh on an open page queries the new day after midnight', async () => {
  let now = new Date(2026,8,19,23,50).getTime();
  class Clock extends Date {
    constructor(...args) {super(...(args.length ? args : [now]));}
    static now() {return now;}
  }
  const queries = [];
  const {App} = load(Clock, async (name, args = {}) => {
    queries.push({name,args});
    return {rows:name === 'get_user_progress' ? [{total_xp:0,current_level:1}] : [],count:0};
  });
  App.prototype.init = async () => {};
  const app = new App();
  app.showLoading = () => {}; // DOM only; retain real refresh and date logic.
  app.showError = message => {throw new Error(message);};
  now += 20*60*1000;
  await app.loadData();
  const today = queries.find(q => q.name === 'get_today_progress');
  assert(today, 'Refresh must load the current completion state');
  assert.equal(today.args.check_in_date, '2026-09-20');
});

test('production history shows cumulative XP in chronological order even when displayed newest first', () => {
  const {App, rows} = load();
  const app = Object.create(App.prototype);
  // The real DB keeps rewards in habits, not check_ins. Supply both inputs to
  // exercise implementations that correctly join the stored habit definitions.
  app.habits = [
    {habit_id:1, name:'Earlier', xp_reward:40},
    {habit_id:2, name:'Later', xp_reward:30},
  ];
  app.renderHistory([
    {check_in_date:'2026-09-18', habit_id:1, name:'Earlier', xp_reward:40},
    {check_in_date:'2026-09-19', habit_id:2, name:'Later', xp_reward:30},
  ]);
  const cells = rows.map(row => [...row.matchAll(/<td>([\s\S]*?)<\/td>/g)].map(m => m[1].trim()));
  assert.equal(cells.length, 2);
  assert.equal(cells[0][1], 'Later');
  assert.equal(cells[0][4], '70', 'Newest day must include XP earned on earlier days');
  assert.equal(cells[1][4], '40', 'Earlier day must not include future XP');
});

test('check-in on a page left open overnight saves the day of the click', async () => {
  let now = new Date(2026,8,19,23,50).getTime();
  class Clock extends Date {
    constructor(...args) {super(...(args.length ? args : [now]));}
    static now() {return now;}
  }
  const writes = [];
  const {App} = load(Clock, async (name, args = {}) => {
    if (name === 'check_in_habit') writes.push({...args});
    return {rows:[], count:0};
  });
  App.prototype.init = async () => {};
  const app = new App();
  app.showLoading = () => {};
  app.render = () => {}; // Only DOM; retain the actual write and reload methods.
  app.showError = message => {throw new Error(message);};
  now += 20*60*1000;
  await app.checkInHabit(1, 10);
  assert.equal(writes.length, 1);
  assert.equal(writes[0].check_in_date, '2026-09-20',
    'Refreshing the date after insertion cannot repair a check-in saved to yesterday');
});
