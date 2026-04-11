# user-profile

## 1. Name

`user-profile`

## 2. Type

Backend profile/context service.

## 3. Purpose

Stores user planning context, goals, habits, priorities, and preferences used by planner and AI-related modules.

## 4. Current Reality

The service is implemented on PostgreSQL and has a relatively narrow domain model focused on planning/profile context rather than a broad identity platform.

## 5. Entry Points

- Spring Boot app: `org.jarvis.userprofile.UserProfileApplication`
- REST base paths:
  - `/api/v1/user-profile`
  - `/api/v1/profile/preferences`

## 6. Configuration

Main configuration source:

- `apps/user-profile/src/main/resources/application.yaml`

Important settings include:

- server port `8089`
- PostgreSQL datasource
- Flyway schema `user_profile`

## 7. API / WebSocket Surface

Planning-context/profile endpoints:

- `GET /api/v1/user-profile/{userId}/planning-context`
- `GET /api/v1/user-profile/{userId}/goals`
- `POST /api/v1/user-profile/{userId}/goals`
- `GET /api/v1/user-profile/{userId}/habits`
- `POST /api/v1/user-profile/{userId}/habits`
- `GET /api/v1/user-profile/{userId}/priorities`
- `POST /api/v1/user-profile/{userId}/priorities`

Preferences endpoints:

- `GET /api/v1/profile/preferences/{userId}`
- `POST /api/v1/profile/preferences`
- `PUT /api/v1/profile/preferences/{userId}`
- `DELETE /api/v1/profile/preferences/{userId}`
- `GET /api/v1/profile/preferences/health`

No WebSocket endpoint.

## 8. Main Internal Components

- `UserProfileService`
- `UserPreferencesService`
- DTOs for goals, habits, priorities, planning context, and preferences

## 9. Dependencies On Other Services

No mandatory downstream service dependency was confirmed.

## 10. Data / Storage

Uses PostgreSQL with Flyway migrations in schema `user_profile`.

## 11. Security Model

- authenticated users can access their own data
- service-delegated calls can pass a path user ID and are trusted when the auth details mark the request as delegated

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/user-profile -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Implemented.

## 14. Known Gaps / Caveats

- Only goals, habits, priorities, planning context, and preferences were confirmed.
- No broader profile/history/social model was confirmed from code.
