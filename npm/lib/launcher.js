'use strict';
/*
 * sqlgraph-mcp npm 런처 (의존성 없음)
 *
 * 처음 실행할 때 OS 에 맞는 GitHub Release zip(경량 Java 런타임 포함)을 받아
 * ~/.sqlgraph/versions/<버전>/ 에 풀고, 그다음부터는 바로 실행한다.
 *
 * 주의: MCP 서버(serve)는 stdout 으로 프로토콜을 주고받으므로, 이 런처의 모든 안내는 stderr 로만 쓴다.
 *
 * 환경변수
 *   SQLGRAPH_HOME           데이터 폴더 (기본 ~/.sqlgraph)
 *   SQLGRAPH_ZIP            내려받는 대신 쓸 로컬 배포 zip
 *   SQLGRAPH_DOWNLOAD_BASE  릴리스 다운로드 주소 (사내 미러 등). 기본 GitHub Releases
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const https = require('https');
const { spawn } = require('child_process');

const REPO = 'ysw2076/sqlgraph-mcp';
const VERSION = require('../package.json').version;

function log(msg) {
  process.stderr.write(`[sqlgraph-mcp] ${msg}\n`);
}

function platformAsset() {
  const key = `${process.platform}-${process.arch}`;
  const map = {
    'win32-x64': 'windows-x64',
    'win32-arm64': 'windows-x64', // Windows on ARM 은 x64 에뮬레이션으로 실행
    'darwin-arm64': 'macos-arm64',
    'linux-x64': 'linux-x64',
  };
  const id = map[key];
  if (!id) {
    throw new Error(
      `${key} 용 배포 파일이 없습니다. JDK 25 가 있다면 Releases 의 sqlgraph-mcp-${VERSION}-all.jar 를 ` +
        `'java -jar' 로 실행하세요: https://github.com/${REPO}/releases`);
  }
  return `sqlgraph-mcp-${id}.zip`;
}

function homeDir() {
  return process.env.SQLGRAPH_HOME || path.join(os.homedir(), '.sqlgraph');
}

function appDir() {
  return path.join(homeDir(), 'versions', VERSION, 'sqlgraph');
}

function javaPath(dir) {
  return path.join(dir, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
}

function isReady(dir) {
  return fs.existsSync(path.join(dir, '.complete')) && fs.existsSync(javaPath(dir)) &&
    fs.existsSync(path.join(dir, 'lib', 'sqlgraph-mcp.jar'));
}

// ------------------------------------------------------------------ download

function download(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) return reject(new Error('리다이렉트가 너무 많습니다'));
    const req = https.get(url, { headers: { 'User-Agent': `sqlgraph-mcp-npm/${VERSION}` } }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
        res.resume();
        return resolve(download(new URL(res.headers.location, url).toString(), redirects + 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${res.statusCode} ${url}`));
      }
      const total = Number(res.headers['content-length'] || 0);
      const chunks = [];
      let got = 0;
      let lastPct = -10;
      res.on('data', (c) => {
        chunks.push(c);
        got += c.length;
        if (total) {
          const pct = Math.floor((got / total) * 100);
          if (pct >= lastPct + 10) {
            lastPct = pct;
            log(`다운로드 ${pct}% (${(got / 1e6).toFixed(1)}MB / ${(total / 1e6).toFixed(1)}MB)`);
          }
        }
      });
      res.on('end', () => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    });
    req.setTimeout(120000, () => req.destroy(new Error('다운로드 시간 초과')));
    req.on('error', reject);
  });
}

// ---------------------------------------------------------------------- unzip

/** zip 의 central directory 를 읽어 압축을 푼다 (stored / deflate). 유닉스 권한도 반영 */
function unzip(buf, dest) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('zip 형식이 아닙니다');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const root = path.resolve(dest);
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('zip central directory 가 손상됐습니다');
    const method = buf.readUInt16LE(p + 10);
    const csize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const extAttr = buf.readUInt32LE(p + 38);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    p += 46 + nameLen + extraLen + commentLen;

    const target = path.resolve(root, name);
    if (target !== root && !target.startsWith(root + path.sep)) throw new Error(`잘못된 경로: ${name}`);
    if (name.endsWith('/')) {
      fs.mkdirSync(target, { recursive: true });
      continue;
    }
    const lNameLen = buf.readUInt16LE(local + 26);
    const lExtraLen = buf.readUInt16LE(local + 28);
    const start = local + 30 + lNameLen + lExtraLen;
    const data = buf.subarray(start, start + csize);
    let out;
    if (method === 0) out = data;
    else if (method === 8) out = zlib.inflateRawSync(data);
    else throw new Error(`지원하지 않는 압축 방식 ${method}: ${name}`);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, out);
    const mode = (extAttr >>> 16) & 0o777;
    if (process.platform !== 'win32' && mode) fs.chmodSync(target, mode);
  }
}

// --------------------------------------------------------------------- ensure

async function ensureInstalled() {
  const dir = appDir();
  if (isReady(dir)) return dir;

  const asset = platformAsset();
  let zip;
  if (process.env.SQLGRAPH_ZIP) {
    log(`로컬 배포 파일 사용: ${process.env.SQLGRAPH_ZIP}`);
    zip = fs.readFileSync(process.env.SQLGRAPH_ZIP);
  } else {
    const base = process.env.SQLGRAPH_DOWNLOAD_BASE || `https://github.com/${REPO}/releases/download/v${VERSION}`;
    const url = `${base.replace(/\/$/, '')}/${asset}`;
    log(`처음 실행: ${asset} 를 내려받습니다 (Java 런타임 포함, 한 번만)`);
    try {
      zip = await download(url);
    } catch (e) {
      throw new Error(`${e.message}\n  네트워크가 막혀 있으면 Releases 에서 ${asset} 를 받아 SQLGRAPH_ZIP 환경변수로 지정하세요:\n  https://github.com/${REPO}/releases/tag/v${VERSION}`);
    }
  }

  // 임시 폴더에 풀고 이름 바꾸기 → 동시에 여러 개가 실행돼도 안전
  const versionDir = path.dirname(dir);
  fs.mkdirSync(versionDir, { recursive: true });
  const tmp = fs.mkdtempSync(path.join(versionDir, '.tmp-'));
  try {
    unzip(zip, tmp);
    const extracted = path.join(tmp, 'sqlgraph');
    if (!fs.existsSync(javaPath(extracted))) throw new Error('배포 파일 구성이 올바르지 않습니다');
    fs.writeFileSync(path.join(extracted, '.complete'), VERSION);
    try {
      fs.renameSync(extracted, dir);
    } catch (e) {
      if (!isReady(dir)) throw e; // 다른 프로세스가 먼저 설치했으면 그대로 사용
    }
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
  log(`설치 완료: ${dir}`);
  return dir;
}

/**
 * Claude Code 에 등록할 명령.
 * - npx 로 실행 중이면: npx -y sqlgraph-mcp serve (Windows 는 cmd /c 필요)
 * - npm i -g 로 설치했으면: node <이 스크립트> serve (경로가 고정됨)
 */
function registerCommand() {
  const script = path.resolve(__dirname, '..', 'bin', 'sqlgraph-mcp.js');
  const viaNpx = process.env.npm_command === 'exec' || /[\\/]_npx[\\/]/.test(script);
  if (viaNpx) {
    const base = ['npx', '-y', 'sqlgraph-mcp', 'serve'];
    return process.platform === 'win32' ? ['cmd', '/c', ...base] : base;
  }
  return [process.execPath, script, 'serve'];
}

async function main(args) {
  let dir;
  try {
    dir = await ensureInstalled();
  } catch (e) {
    log(`오류: ${e.message}`);
    process.exit(1);
  }
  const env = { ...process.env, SQLGRAPH_REGISTER_CMD: JSON.stringify(registerCommand()) };
  const child = spawn(javaPath(dir),
    ['--enable-native-access=ALL-UNNAMED', '-jar', path.join(dir, 'lib', 'sqlgraph-mcp.jar'), ...args],
    { stdio: 'inherit', env });
  const forward = (sig) => { try { child.kill(sig); } catch (_) { /* 이미 종료 */ } };
  process.on('SIGINT', forward);
  process.on('SIGTERM', forward);
  child.on('error', (e) => { log(`실행 실패: ${e.message}`); process.exit(1); });
  child.on('exit', (code, signal) => process.exit(signal ? 1 : (code ?? 0)));
}

module.exports = { main, unzip, platformAsset, registerCommand, VERSION };
