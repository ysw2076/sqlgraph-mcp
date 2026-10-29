"""CI 실패 원인을 GitHub 주석(::error)으로 남긴다.
전체 로그를 열지 않아도 Actions 요약 화면이나 API(check-runs annotations)로 원인을 볼 수 있게 하려는 것."""
import glob
import sys
import xml.etree.ElementTree as ET


def esc(s: str) -> str:
    return s.replace('%', '%25').replace('\r', '').replace('\n', '%0A')


found = 0
for f in glob.glob('**/build/test-results/test/*.xml', recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for tc in root.iter('testcase'):
        for kind in ('failure', 'error'):
            el = tc.find(kind)
            if el is None:
                continue
            found += 1
            name = f"{tc.get('classname', '').split('.')[-1]}.{tc.get('name')}"
            body = (el.get('message') or '') + '\n' + (el.text or '')
            print(f"::error title=Test failed: {esc(name)}::{esc(body[:3000])}")

if found == 0:
    # 테스트가 아니라 컴파일/패키징 실패: Gradle 출력 끝부분
    try:
        with open('gradle.log', encoding='utf-8', errors='replace') as fh:
            lines = fh.read().splitlines()
        tail = '\n'.join(l for l in lines[-60:] if l.strip())
        print(f"::error title=Gradle output (last lines)::{esc(tail[-6000:])}")
    except FileNotFoundError:
        print('::error title=No gradle.log::Gradle did not produce output')
sys.exit(0)
