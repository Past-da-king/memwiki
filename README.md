<div align="center">

<img src="app/src/main/res/drawable/ic_launcher_main.png" width="128" alt="MemWiki" />

# MemWiki

**Your wiki, alive.** Drop notes, voice memos, images, or URLs. They compile into interlinked pages you can search, chat with, and audit.

</div>

---

## What it is

MemWiki is an Android app that turns raw input into a personal, ever-evolving knowledge graph. Every note becomes one or more pages. Every mention of a person, project, or concept is auto-linked with `[[wiki-style]]` brackets. You can:

- **Capture** text, audio, multiple images, or URLs in a single draft.
- **Compile to wiki** with one tap — Gemini analyses the input and updates the right pages.
- **Search** your wiki by asking questions in plain language.
- **Audit** the graph for contradictions, missing pages, and unlinked mentions, then ask the agent to fix them.
- **Record from anywhere** via a Quick Settings tile — tap it, walk away, tap stop, the audio auto-ingests.

## Why

A wiki on its own is just a notebook with prettier formatting. The point of MemWiki is that every new thing you drop *consolidates* with everything that came before. After a week of jotting fragments, you have a structured, queryable knowledge base instead of a pile of notes.

## Features

| | |
|---|---|
| **Multimodal capture** | Text, voice memos, multiple images per note, URLs — all in one ingest call. |
| **Auto-linking** | Every entity becomes a `[[link]]`. Missing pages are auto-stubbed. |
| **URL grounding** | Drop a URL and Gemini fetches the page + searches the web for context. |
| **Two-pass retrieval** | Above 6 pages, the agent first scans only titles+tags to decide what to load — keeps cost flat as the wiki grows. |
| **Live agent thinking** | See the model's chain-of-thought stream in markdown as it works. Tap the home banner to open the full thinking sheet. |
| **Audit & Fix** | Two-button audit flow: scan for issues, then let the agent rewrite pages to resolve them. |
| **QS tile recording** | Foreground service with persistent notification + chronometer. Tap once to start, tap stop to auto-ingest. 15-min cap to fit Gemini's audio window. |
| **Three themes** | Default (neutral B&W), Adaptive (Material You from your wallpaper), Editorial (warm-dark parchment). |
| **Fullscreen image viewer** | Tap any wiki image to view it full-screen. |
| **Task editing** | Tap any task/reminder card to edit title, category, date, and description. |

## Setup

1. Install the latest APK from the [Releases](../../releases) page (sideload — enable "Install unknown apps" for your browser).
2. Open MemWiki. The onboarding walks you through:
   - Brief intro to what the app does
   - Paste a Gemini API key (free at [aistudio.google.com/apikey](https://aistudio.google.com/apikey))
   - Pick a model from the live list your key has access to
3. Done. Start adding notes from the Notes tab, or pull down Quick Settings and add the **MemWiki Record** tile for hands-free voice capture.

> Your API key lives in app-private SharedPreferences. It's never sent anywhere except Google's Gemini API.

## Build from source

**Requirements:** Android Studio, JDK 17+, Android SDK 36.

```bash
git clone https://github.com/Past-da-king/memwiki.git
cd memwiki
cp .env.example .env       # add your GEMINI_API_KEY for compile-time fallback
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

- **UI:** Jetpack Compose, Material 3, custom magazine-style theme.
- **State:** ViewModel + StateFlow.
- **Persistence:** Room (`wiki_pages`, `raw_sources`, `reminders`, `activity_logs`).
- **AI:** Gemini REST API via Retrofit/OkHttp. SSE streaming for `streamGenerateContent`. Auto-retry on 503/429/5xx with exponential backoff.
- **Background recording:** Foreground service + Quick Settings TileService.

## Status

Personal project, sideload-only. Not on Play Store. Pull requests and issues welcome.

## Licence

MIT — see [LICENSE](LICENSE) if present, otherwise treat as MIT until I add one.
