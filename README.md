# transport

`transport` is a Kotlin service and React UI for viewing live London rail services on a projected map.

It pulls TfL arrival-board data, assembles it into a rail snapshot, projects services onto imported rail geometry, and serves the browser UI plus the minimal HTTP routes that UI needs.

Supported modes:
- Tube
- Elizabeth line
- London Overground
- Tram

## Architecture

The repository follows a simple hexagonal layout:

- `domain`: domain types, services, projection logic, and ports
- `http`: TfL HTTP client adapters and payload parsing support
- `json`: `kotlinx.serialization` DTOs
- `service`: Ktor composition root, HTTP API, config, and packaged UI
- `ui`: React + Vite + Leaflet frontend

## Requirements

- JDK 21
- Node.js and npm
- A TfL Unified API subscription key

## Running

Run the backend and packaged UI together:

```bash
export TFL_SUBSCRIPTION_KEY=your-key
./gradlew :service:run
```

Then open `http://127.0.0.1:8080`.

## Movement Model

The map does not use onboard GPS. Service positions are inferred from:

- TfL arrival predictions
- resolved next-stop station metadata
- imported rail geometry
- observed departures from the previous next-stop station

Current location text in the UI comes directly from TfL prediction data and is not the same thing as the projected map coordinate.

Under the current projection rules:

- if `nextStop` is unknown, the service is not plotted
- if `nextStop` is known but no departure has been observed yet, the service is plotted at `nextStop`
- once the service has been observed leaving station `A` for adjacent station `B`, it is interpolated between `A` and `B` using TfL's `expectedArrival` for `B`

## Development

Useful commands:

```bash
./gradlew test
./gradlew clean build
./gradlew testExternal
./pre-commit
```

Notes:

- `./pre-commit` is the main local gate and runs `./gradlew clean build`
- `./gradlew testExternal` runs live tests against the TfL API and requires `TFL_SUBSCRIPTION_KEY`
- the service build packages the Vite production bundle into the Ktor app resources
