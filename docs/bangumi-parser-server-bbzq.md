# BBZQ Parser Server Backend Changes

This document applies to `https://github.com/guhfvbj/biliroaming-ts-server-vercel` at the
current Resin deployment revision. It has one build and three Next.js processes:

| Process | Port | `BILI_REGION` | Resin platform | BBZQ region |
| --- | --- | --- | --- | --- |
| `bilihk` | `3101` | `hk` | `BiliHK` | `hk` |
| `bilitw` | `3102` | `tw` | `BiliTW` | `tw` |
| `bilisea` | `3103` | `sea` | `BiliSEA` | `th` |

Keep those processes separate. A request sent to one process must always leave through its own
Resin platform; do not implement a direct-connect fallback or a per-request Resin switch.
Expose them through three reverse-proxy upstreams. BBZQ's Hong Kong, Taiwan, and Southeast Asia
server entries must point to the respective upstream. The mainland entry is optional.

## Required configuration changes

In `src/_config.ts`, add:

```ts
export const bbzq_enabled: io = 1;
export const fs_enabled: io = 0;
export const need_login: io = 0;
```

`BILI_REGION` is already supplied by the systemd environment. Derive the BBZQ region at runtime:

```ts
export const bbzq_region = (() => {
  switch (process.env.BILI_REGION?.trim().toLowerCase()) {
    case "hk": return "hk" as const;
    case "tw": return "tw" as const;
    case "sea": return "th" as const;
    default: return null;
  }
})();
```

Both `pages/api/legacy/x/v2/search/type.ts` and
`pages/api/legacy/intl/gateway/v2/app/search/type.ts` must add `basic_res` only when
`env.fs_enabled === 1`. The default must return only upstream search items.

## BBZQ request access

In `src/utils/player-data-handler/app.ts`, accept BBZQ while retaining the BiliRoaming policy:

```ts
const fromBiliRoaming = Boolean(headers["x-from-biliroaming"]);
const fromBbzq = Boolean(headers["platform-from-bbzq"]);

if (!fromBiliRoaming && !fromBbzq && env.web_on === 0) return [false, 1];
if (fromBiliRoaming && env.ver_min !== 0 && env.ver_min > Number(headers["build"])) {
  return [false, 2];
}
```

After parsing query parameters, when `need_login === 0` and `access_key` is missing, return
`[true, 0]` before calling `access_keyParams2info()`. Do not log anonymous requests as if they
had credentials.

## Endpoint fixes

- In `pages/api/legacy/intl/gateway/v2/ogv/view/app/season.ts`, always call `res.json(m_res)`.
  Its current `th_subtitle_api` branch can leave a successful request without a response.
- Retain the existing `resinFetch` and `withResinError` wrappers for every Bilibili upstream.
  A Resin failure must produce the existing `503` error and never fall back to `fetch`.

## Compatibility endpoint

Add `pages/api/bbzq/compat.ts`. It is local metadata only: do not proxy Bilibili, inspect access
keys, or cache it.

```ts
import type { NextApiRequest, NextApiResponse } from "next";
import * as env from "../../../src/_config";

export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  const region = env.bbzq_region;
  if (!env.bbzq_enabled || !region) {
    res.status(404).json({ code: -404, message: "BBZQ compatibility is disabled" });
    return;
  }
  res.setHeader("Cache-Control", "no-store");
  res.status(200).json({
    code: 0,
    data: {
      protocol: "bbzq-bangumi/1",
      region,
      capabilities: region === "th"
        ? ["search", "season", "playurl", "subtitle", "grpc-playurl-v1", "grpc-playurl-v2"]
        : ["search", "season", "playurl", "grpc-playurl-v1", "grpc-playurl-v2"],
    },
  });
}
```

In `next.config.mjs`, add a `Cache-Control: no-store` header rule for `/api/bbzq/compat` before
the catch-all cache rule.

## MOSS / gRPC v2

The current server already transparently proxies `bilibili.pgc.gateway.player.v1.PlayURL` through
Resin. Add the equivalent rewrite and target for v2:

```js
{
  source: "/bilibili.pgc.gateway.player.v2.PlayURL/:path(.*)",
  destination: "/api/legacy/grpc/pgc-playurl-v2/:path*",
}
```

In `pages/api/legacy/grpc/[...path].ts`, add:

```ts
"pgc-playurl-v2":
  "https://app.bilibili.com/api/grpc/bilibili.pgc.gateway.player.v2.PlayURL",
```

The generic handler must preserve the request byte stream, gRPC headers, response headers,
status, and response bytes. It only changes the upstream transport to `resinFetch`; it must not
send MOSS traffic to legacy JSON endpoints.

## Reverse proxy and acceptance

Map public upstreams to `127.0.0.1:3101`, `:3102`, and `:3103`. Configure those three addresses
in BBZQ as HK, TW, and TH respectively, then use the in-app server test. A passing check requires
the endpoint's declared region to match the configured BBZQ region and requires the listed
capabilities. Older backends without `/api/bbzq/compat` remain usable but show a basic playback
check only.
