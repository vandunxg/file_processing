# JWT Environment Variables

## Scope

Supply the JWT environment variables referenced by `application.yml` from local environment files.

## Files

- `.env` maps the current local key material to `APP_AUTH_JWT_ACTIVE_KID`,
  `APP_AUTH_JWT_PRIVATE_KEY_PEM_BASE64`, and `APP_AUTH_JWT_PUBLIC_KEY_PEM_BASE64`.
- `.env.example` documents the same variable names, retains the non-secret active key ID, and leaves
  both PEM values empty.

## Security

`.env` remains ignored. No private key or external-service credential is added to `.env.example`.

## Verification

Confirm the expected variables exist in both files and that the tracked example contains no PEM value.
