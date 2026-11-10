// 生成离线地图数据：assets/geo/land.js、countries.js、cities.js
// 数据源：
//   - 海岸线/国界：world-atlas (Natural Earth 110m, public domain) via unpkg
//   - 城市坐标：GeoNames cities15000 (CC BY 4.0) via download.geonames.org
//   - 中文名：GeoNames 别名启发式 + 常见城市覆盖表（见 OVERRIDES）
// 用法：node tools/build-geo.mjs
import { existsSync } from "node:fs";
import { mkdir, open, readFile, stat, writeFile } from "node:fs/promises";
import { createReadStream } from "node:fs";
import { inflateRawSync } from "node:zlib";

const ASSETS = new URL("../assets/geo/", import.meta.url);
const CACHE = new URL("../.cache/", import.meta.url);
await mkdir(ASSETS, { recursive: true });
await mkdir(CACHE, { recursive: true });

async function download(url, name) {
  const dest = new URL(name, CACHE);
  if (existsSync(dest)) {
    const s = await stat(dest);
    if (s.size > 1024) {
      console.log("cache hit", name, (s.size / 1048576).toFixed(1) + "MB");
      return dest;
    }
  }
  const t0 = Date.now();
  const r = await fetch(url, { redirect: "follow" });
  if (!r.ok) throw new Error(url + " -> " + r.status);
  const buf = Buffer.from(await r.arrayBuffer());
  await writeFile(dest, buf);
  console.log("downloaded", name, (buf.length / 1048576).toFixed(1) + "MB",
    ((Date.now() - t0) / 1000).toFixed(0) + "s");
  return dest;
}

function unzipFirstEntry(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("no zip eocd");
  const p0 = buf.readUInt32LE(eocd + 16);
  if (buf.readUInt32LE(p0) !== 0x02014b50) throw new Error("bad central dir");
  const method = buf.readUInt16LE(p0 + 10);
  const compSize = buf.readUInt32LE(p0 + 20);
  const nameLen = buf.readUInt16LE(p0 + 28);
  const localOff = buf.readUInt32LE(p0 + 42);
  const lNameLen = buf.readUInt16LE(localOff + 26);
  const lExtraLen = buf.readUInt16LE(localOff + 28);
  const dataStart = localOff + 30 + lNameLen + lExtraLen;
  const raw = buf.subarray(dataStart, dataStart + compSize);
  return { data: method === 0 ? raw : inflateRawSync(raw) };
}

function decodeTopo(topo) {
  const [sx, sy] = topo.transform.scale;
  const [tx, ty] = topo.transform.translate;
  const arcs = topo.arcs.map(arc => {
    let x = 0, y = 0;
    return arc.map(([dx, dy]) => {
      x += dx; y += dy;
      return [Math.round((x * sx + tx) * 1000) / 1000, Math.round((y * sy + ty) * 1000) / 1000];
    });
  });
  function ring(idxs) {
    const pts = [];
    for (const i of idxs) {
      const a = i < 0 ? arcs[~i].slice().reverse() : arcs[i];
      if (pts.length && a.length) pts.push(...a.slice(1));
      else pts.push(...a);
    }
    return pts;
  }
  function geom(g) {
    if (g.type === "Polygon") return { type: "Polygon", coordinates: g.arcs.map(ring) };
    if (g.type === "MultiPolygon") return { type: "MultiPolygon", coordinates: g.arcs.map(p => p.map(ring)) };
    return null;
  }
  return { geom };
}

// 常见城市中文名覆盖（GeoNames 别名质量参差，主要城市以此为准）
const OVERRIDES = {
  Beijing: "北京", Shanghai: "上海", Guangzhou: "广州", Shenzhen: "深圳", Chengdu: "成都",
  Chongqing: "重庆", Tianjin: "天津", Hangzhou: "杭州", Wuhan: "武汉", Xian: "西安",
  Nanjing: "南京", Suzhou: "苏州", Zhengzhou: "郑州", Changsha: "长沙", Qingdao: "青岛",
  Shenyang: "沈阳", Dalian: "大连", Xiamen: "厦门", Fuzhou: "福州", Jinan: "济南",
  Hefei: "合肥", Kunming: "昆明", Harbin: "哈尔滨", Changchun: "长春", Nanning: "南宁",
  Nanchang: "南昌", Guiyang: "贵阳", Lanzhou: "兰州", Taiyuan: "太原", Shijiazhuang: "石家庄",
  Urumqi: "乌鲁木齐", Lhasa: "拉萨", Hohhot: "呼和浩特", Yinchuan: "银川", Xining: "西宁",
  Haikou: "海口", Ningbo: "宁波", Wenzhou: "温州", Wuxi: "无锡", Dongguan: "东莞",
  Foshan: "佛山", Zhuhai: "珠海", Huizhou: "惠州", Zhongshan: "中山", Jiangmen: "江门",
  Zhanjiang: "湛江", Baoding: "保定", Tangshan: "唐山", Luoyang: "洛阳", Kaifeng: "开封",
  Xuzhou: "徐州", Yangzhou: "扬州", Changzhou: "常州", Nantong: "南通", Shaoxing: "绍兴",
  Jiaxing: "嘉兴", Taizhou: "台州", Jinhua: "金华", Quanzhou: "泉州", Zhangzhou: "漳州",
  Shantou: "汕头", Guilin: "桂林", Liuzhou: "柳州", Beihai: "北海", Sanya: "三亚",
  Yantai: "烟台", Weifang: "潍坊", Zibo: "淄博", Linyi: "临沂", Jining: "济宁",
  Daqing: "大庆", Qiqihar: "齐齐哈尔", Anshan: "鞍山", Fushun: "抚顺", Jilin: "吉林",
  Baotou: "包头", Datong: "大同", Weihai: "威海", Mianyang: "绵阳", Nanchong: "南充",
  Yibin: "宜宾", Zigong: "自贡", Luzhou: "泸州", Deyang: "德阳", Leshan: "乐山",
  "Hong Kong": "香港", Macau: "澳门", Taipei: "台北", Taichung: "台中", Kaohsiung: "高雄",
  Tokyo: "东京", Osaka: "大阪", Kyoto: "京都", Seoul: "首尔", Busan: "釜山",
  Singapore: "新加坡", Bangkok: "曼谷", Hanoi: "河内", "Kuala Lumpur": "吉隆坡",
  London: "伦敦", Paris: "巴黎", Berlin: "柏林", Rome: "罗马", Madrid: "马德里",
  Moscow: "莫斯科", Dubai: "迪拜", "New York": "纽约", "Los Angeles": "洛杉矶",
  "San Francisco": "旧金山", Chicago: "芝加哥", Toronto: "多伦多", Vancouver: "温哥华",
  Sydney: "悉尼", Melbourne: "墨尔本", Auckland: "奥克兰", Cairo: "开罗",
  Istanbul: "伊斯坦布尔", Amsterdam: "阿姆斯特丹", Zurich: "苏黎世", Vienna: "维也纳",
};

const KANA_HANGUL = /[\u3040-\u30ff\uac00-\ud7af]/;

// ---------- 下载 ----------
const landTopo = JSON.parse(await fetch("https://unpkg.com/world-atlas@2/land-110m.json").then(r => r.text()));
const countriesTopo = JSON.parse(await fetch("https://unpkg.com/world-atlas@2/countries-110m.json").then(r => r.text()));
const cityZipPath = await download("https://download.geonames.org/export/dump/cities15000.zip", "cities15000.zip");

const land = decodeTopo(landTopo);
const landFeatures = (landTopo.objects.land.geometries || []).map(g => ({
  type: "Feature", properties: {}, geometry: land.geom(g),
})).filter(f => f.geometry);

const countries = decodeTopo(countriesTopo);
const countryFeatures = (countriesTopo.objects.countries.geometries || []).map(g => ({
  type: "Feature",
  properties: { name: (g.properties && g.properties.name) || "" },
  geometry: g.type === "GeometryCollection" ? null : countries.geom(g),
})).filter(f => f.geometry);

// ---------- 城市 ----------
const citiesZip = await readFile(cityZipPath);
const txt = unzipFirstEntry(citiesZip).data.toString("utf8");
const CJK = /[\u3400-\u4dbf\u4e00-\u9fff]/;
const base = [];
for (const line of txt.split("\n")) {
  if (!line.trim()) continue;
  const f = line.split("\t");
  if (f.length < 15) continue;
  const lat = parseFloat(f[4]);
  const lng = parseFloat(f[5]);
  if (f[6] !== "P") continue;
  const pop = parseInt(f[14] || "0", 10) || 0;
  const country = f[8];
  if (country !== "CN" && pop < 300000 && f[7] !== "PPLC") continue;
  base.push({ n: f[2], y: lat, x: lng, p: pop, c: country, alt: f[3] || "" });
}

function zhName(c) {
  const ov = OVERRIDES[c.n];
  if (ov) return ov;
  const cands = c.alt.split(",").filter(n =>
    CJK.test(n) && n.length <= 12 && !KANA_HANGUL.test(n));
  let best = "";
  for (const n of cands) { if (n.endsWith("市") && n.length >= 2) { best = n; break; } }
  if (!best) { for (const n of cands) { if (n.length >= 2 && n.length <= 6) { best = n; break; } } }
  if (!best && cands.length) { best = cands[0]; }
  return best.replace(/市$/, "");
}

const cities = base.map(c => ({
  n: c.n,
  z: zhName(c),
  y: Math.round(c.y * 10000) / 10000,
  x: Math.round(c.x * 10000) / 10000,
  p: c.p,
  c: c.c,
}));
cities.sort((a, b) => b.p - a.p);
const trimmed = cities.slice(0, 6000);

const landOut = { type: "FeatureCollection", features: landFeatures };
const countriesOut = { type: "FeatureCollection", features: countryFeatures };

await writeFile(new URL("land.js", ASSETS), "window.GEO_LAND=" + JSON.stringify(landOut) + ";");
await writeFile(new URL("countries.js", ASSETS), "window.GEO_COUNTRIES=" + JSON.stringify(countriesOut) + ";");
await writeFile(new URL("cities.js", ASSETS), "window.GEO_CITIES=" + JSON.stringify(trimmed) + ";");

const kb = b => (b.length / 1024).toFixed(1) + " KB";
console.log("land.js       ", kb(JSON.stringify(landOut)), "features:", landFeatures.length);
console.log("countries.js  ", kb(JSON.stringify(countriesOut)), "features:", countryFeatures.length);
console.log("cities.js     ", kb(JSON.stringify(trimmed)), "cities:", trimmed.length);
console.log("CN cities:", trimmed.filter(c => c.c === "CN").length,
  " with zh:", trimmed.filter(c => c.z).length);
