# Metrolist Desktop

Metrolist Desktop is a community-built YouTube Music client for desktop platforms, powered by Kotlin and Compose Multiplatform.

> **Unofficial project:** This repository is independent from the upstream Metrolist Android project. It is **not maintained, endorsed, or officially supported by Metrolist or MetrolistGroup**. Please do not report desktop issues to the upstream Android project.

## Why this project exists

The goal is to give Metrolist a focused desktop experience while keeping the codebase approachable for contributors. The project is evolving, and there is plenty of room to improve the UI, playback reliability, search, packaging, and cross-platform support.

## Current highlights

- Native desktop UI built with Compose Multiplatform
- YouTube Music search, playback, queues, lyrics, and playlists
- Desktop media controls and native platform integrations
- Listen Together support
- Shared networking and YouTube Music data layers

## Project structure

```text
desktop/   Desktop application and platform integrations
shared/    Shared models, services, and application logic
innertube/ YouTube Music API and data access layer
```

## Getting started

### Requirements

- JDK 21 or newer
- A working desktop environment
- `mpv` installed and available on your `PATH`

### Build

```bash
git clone https://github.com/mumtazka/metrolist.git
cd metrolist
./gradlew :desktop:compileKotlinJvm
```

To run the application from Gradle:

```bash
./gradlew :desktop:run
```

## Contributing

Contributions are very welcome. Whether you want to fix a bug, improve the desktop UX, add tests, polish a platform integration, or help with documentation, your work can make this project better.

Good places to start:

- Improve playback and queue reliability
- Refine search results and ranking
- Test Listen Together across different networks
- Improve Windows, macOS, and Linux packaging
- Add automated tests and developer tooling
- Polish accessibility, keyboard shortcuts, and responsive layouts

### Suggested workflow

1. Open an issue or check the existing issues before starting a large change.
2. Create a focused branch from `main`.
3. Keep changes small and explain the reason behind them.
4. Run `./gradlew :desktop:compileKotlinJvm` before opening a pull request.
5. Open a pull request with a clear description and testing notes.

If you are unsure where to begin, open an issue and say what you would like to work on. Small improvements are valuable too.

## Community and project status

This is a community project looking for maintainers and contributors. It is not a commercial product, and features may change as the project develops. Please be respectful, provide reproducible bug reports, and mention your operating system when reporting desktop-specific issues.

## License

See [LICENSE](LICENSE) for the license terms.

