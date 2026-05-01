const http = require("http");
const { URL } = require("url");

function parseCookies(h) {
  const out = {};
  const s = String(h || "");
  if (!s) return out;
  for (const part of s.split(";")) {
    const idx = part.indexOf("=");
    if (idx <= 0) continue;
    const k = part.slice(0, idx).trim();
    const v = part.slice(idx + 1).trim();
    if (!k) continue;
    out[k] = v;
  }
  return out;
}

function formEncode(obj) {
  return Object.entries(obj)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join("&");
}

async function postForm(urlStr, body) {
  const u = new URL(urlStr);
  const lib = u.protocol === "https:" ? require("https") : require("http");
  const opts = {
    method: "POST",
    hostname: u.hostname,
    port: u.port || (u.protocol === "https:" ? 443 : 80),
    path: u.pathname + (u.search || ""),
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      "content-length": Buffer.byteLength(body),
    },
  };
  return await new Promise((resolve, reject) => {
    const r = lib.request(opts, (res) => {
      let data = "";
      res.on("data", (c) => (data += c));
      res.on("end", () => resolve({ status: res.statusCode || 0, headers: res.headers || {}, body: data }));
    });
    r.on("error", reject);
    r.write(body);
    r.end();
  });
}

function setCookie(name, value, opts) {
  const parts = [`${name}=${value}`];
  if (opts && opts.maxAge !== undefined) parts.push(`Max-Age=${opts.maxAge}`);
  if (opts && opts.path) parts.push(`Path=${opts.path}`);
  if (opts && opts.httpOnly) parts.push("HttpOnly");
  if (opts && opts.secure) parts.push("Secure");
  if (opts && opts.sameSite) parts.push(`SameSite=${opts.sameSite}`);
  return parts.join("; ");
}

function base64urlDecode(s) {
  const str = String(s || "").replace(/-/g, "+").replace(/_/g, "/");
  const pad = str.length % 4 ? "=".repeat(4 - (str.length % 4)) : "";
  return Buffer.from(str + pad, "base64").toString("utf8");
}

function decodeJwtPayload(jwt) {
  const t = String(jwt || "");
  const parts = t.split(".");
  if (parts.length < 2) return null;
  try {
    const json = base64urlDecode(parts[1]);
    return JSON.parse(json);
  } catch {
    return null;
  }
}

function isJwtExpired(payload) {
  const exp = payload && typeof payload.exp === "number" ? payload.exp : null;
  if (!exp) return false;
  const now = Math.floor(Date.now() / 1000);
  return now >= exp;
}

const PORT = Number(process.env.PORT || "8080");
const TOKEN_URL =
  process.env.KEYCLOAK_TOKEN_URL ||
  "http://keycloak-service.rhcl-keycloak.svc.cluster.local:8080/auth/realms/rhcl/protocol/openid-connect/token";
const CLIENT_ID = process.env.OIDC_CLIENT_ID || "rhcl-ui";

const server = http.createServer(async (req, res) => {
  try {
    const host = String(req.headers["x-forwarded-host"] || req.headers.host || "example.com");
    const u = new URL(req.url || "/", `http://${host}`);
    const cookies = parseCookies(req.headers.cookie);

    if (u.pathname === "/whoami") {
      const tok = String(cookies.rhcl_token || "");
      if (!tok) {
        res.writeHead(401, { "content-type": "application/json", "cache-control": "no-store" });
        res.end(JSON.stringify({ ok: false, error: "not_logged_in" }));
        return;
      }
      const payload = decodeJwtPayload(tok) || {};
      if (isJwtExpired(payload)) {
        res.writeHead(401, { "content-type": "application/json", "cache-control": "no-store" });
        res.end(JSON.stringify({ ok: false, error: "token_expired" }));
        return;
      }
      res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
      res.end(
        JSON.stringify(
          {
            ok: true,
            user: {
              username: payload.preferred_username || payload.username || "",
              name: payload.name || "",
              email: payload.email || "",
            },
            token: { exp: payload.exp || null, iat: payload.iat || null, iss: payload.iss || "" },
          },
          null,
          2
        )
      );
      return;
    }

    if (u.pathname === "/logout") {
      const back = `https://${host}/`;
      const keycloakLogout =
        `https://${host}/auth/realms/rhcl/protocol/openid-connect/logout` +
        `?client_id=${encodeURIComponent(CLIENT_ID)}` +
        `&post_logout_redirect_uri=${encodeURIComponent(back)}`;

      const clears = [
        setCookie("rhcl_token", "", { maxAge: 0, path: "/", httpOnly: true, secure: true, sameSite: "Lax" }),
        setCookie("target", "", { maxAge: 0, path: "/", httpOnly: true, secure: true, sameSite: "Lax" }),
        setCookie("KEYCLOAK_SESSION", "", { maxAge: 0, path: "/auth/realms/rhcl/", secure: true, sameSite: "None" }),
        setCookie("KEYCLOAK_IDENTITY", "", {
          maxAge: 0,
          path: "/auth/realms/rhcl/",
          httpOnly: true,
          secure: true,
          sameSite: "None",
        }),
        setCookie("KC_RESTART", "", { maxAge: 0, path: "/auth/realms/rhcl/" }),
      ];

      res.writeHead(302, { location: keycloakLogout, "set-cookie": clears, "cache-control": "no-store" });
      res.end();
      return;
    }

    if (u.pathname !== "/auth/callback") {
      res.writeHead(404, { "content-type": "text/plain" });
      res.end("not found\n");
      return;
    }

    const code = u.searchParams.get("code") || "";
    if (!code) {
      res.writeHead(302, { location: `https://${host}/` });
      res.end();
      return;
    }

    const redirectUri = `https://${host}/auth/callback`;
    const body = formEncode({
      code,
      grant_type: "authorization_code",
      redirect_uri: redirectUri,
      client_id: CLIENT_ID,
    });

    const tr = await postForm(TOKEN_URL, body);
    if (tr.status < 200 || tr.status >= 300) {
      res.writeHead(502, { "content-type": "text/plain" });
      res.end(`token_exchange_failed status=${tr.status}\n${String(tr.body || "").slice(0, 2000)}\n`);
      return;
    }

    let tok = null;
    try {
      tok = JSON.parse(tr.body);
    } catch {
      tok = null;
    }
    const idToken = tok && tok.id_token ? String(tok.id_token) : "";
    if (!idToken) {
      res.writeHead(502, { "content-type": "text/plain" });
      res.end("token_exchange_failed missing id_token\n");
      return;
    }

    const loc = `https://${host}/`;
    const cookieHeaders = [
      setCookie("rhcl_token", idToken, { maxAge: 3600, path: "/", httpOnly: true, secure: true, sameSite: "Lax" }),
      setCookie("target", "", { maxAge: 0, path: "/", httpOnly: true, secure: true, sameSite: "Lax" }),
    ];
    res.writeHead(302, { location: loc, "set-cookie": cookieHeaders, "cache-control": "no-store" });
    res.end();
  } catch (e) {
    res.writeHead(500, { "content-type": "text/plain" });
    res.end(`error: ${String(e)}\n`);
  }
});

server.listen(PORT, "0.0.0.0", () => {
  // eslint-disable-next-line no-console
  console.log(`oidc-callback listening on :${PORT}`);
});

