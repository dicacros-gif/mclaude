# Midjourney Top Gallery

Midjourney의 공개 탐색 페이지(Images / Styles / Videos 3개 탭)를 **매일 자동 크롤링**하여 최신 결과를 누적합니다.

## 구조

```
mclaude/
├── scraper.py           # Playwright 기반 크롤러
├── generate_gallery.py  # index.html 갤러리 생성기
├── requirements.txt
├── index.html           # 자동 생성 갤러리 (GitHub Pages)
├── data/
│   └── metadata.json    # 누적 메타데이터 (최신순)
└── .github/workflows/
    └── daily_crawl.yml  # 매일 UTC 02:00 자동 실행
```

## 자동화 동작

1. GitHub Actions가 매일 UTC 02:00에 실행됩니다.
2. Playwright Chromium으로 Midjourney Explore 3개 탭을 크롤링합니다.
3. 새 항목만 `data/metadata.json` **맨 앞에** 추가합니다 (기존 데이터 보존).
4. `index.html` 갤러리를 재생성하고 커밋합니다.

## GitHub Pages 설정

저장소 Settings → Pages → Source: `main` 브랜치 → `/ (root)` 로 설정하면  
`https://dicacros-gif.github.io/mclaude/` 에서 갤러리를 볼 수 있습니다.

## 인증 (선택)

Midjourney 로그인이 필요한 경우, 브라우저 개발자 도구에서 쿠키를 복사해  
저장소 **Settings → Secrets → MJ_COOKIES** 에 JSON 배열로 저장하세요.

```json
[{"name":"__Secure-next-auth.session-token","value":"...","domain":".midjourney.com","path":"/","secure":true}]
```

## 수동 실행

Actions 탭 → **Daily Midjourney Crawl** → **Run workflow**
