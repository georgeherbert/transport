# transport

`transport` is a Kotlin service and React UI for viewing live London rail services on a projected map.

It pulls TfL arrival-board data, assembles it into a rail snapshot, projects trains onto imported rail geometry, and serves both a JSON API and a browser UI from the same service.

Supported modes:
- Tube
- Elizabeth line
- London Overground
- Tram

`DLR` is currently excluded because the TfL feed does not provide stable vehicle ids, which means the service cannot track DLR vehicles deterministically over time.

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

## Configuration

The service reads configuration from environment variables:

| Variable | Default | Notes |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | Ktor bind host |
| `PORT` | `8080` | Ktor bind port |
| `TUBE_CACHE_TTL_SECONDS` | `20` | Snapshot cache TTL. The name is legacy, but it applies to the rail snapshot service. |
| `RAIL_MAP_POLL_INTERVAL_SECONDS` | `5` | Upstream refresh interval for the live rail map feed |
| `TFL_REQUEST_TIMEOUT_SECONDS` | `10` | TfL HTTP request timeout |
| `TFL_BASE_URL` | `https://api.tfl.gov.uk` | TfL API base URL |
| `TFL_SUBSCRIPTION_KEY` | none | Required |

## Running

Run the backend and packaged UI together:

```bash
export TFL_SUBSCRIPTION_KEY=your-key
./gradlew :service:run
```

Then open `http://127.0.0.1:8080`.

For frontend-only development:

```bash
export TFL_SUBSCRIPTION_KEY=your-key
./gradlew :service:run
cd ui
npm ci
npm run dev
```

The Vite dev server runs on `http://127.0.0.1:5173` and proxies `/api` and `/health` to the backend on port `8080`.

## API

Primary routes:

- `GET /health`
- `GET /api`
- `GET /api/rail/live`
- `GET /api/rail/live?refresh=true`
- `GET /api/rail/lines`
- `GET /api/rail/map`
- `GET /api/rail/map/stream`

Legacy `/api/tubes/*` aliases are still available for compatibility.

The browser UI does one initial fetch and then consumes the live map stream over Server-Sent Events.

## Movement Model

The map does not use onboard GPS. Train positions are inferred from:

- TfL arrival predictions
- resolved next-stop station metadata
- imported rail geometry
- learned timings between adjacent stops

Current location text in the UI comes directly from TfL prediction data and is not the same thing as the projected map coordinate.

Under the current projection rules:

- if `nextStop` is unknown, the train is not plotted
- if `nextStop` is known but no timing has been learned yet, the train is plotted at `nextStop`
- if timing has been learned, the train is interpolated between the previous and next stops

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
