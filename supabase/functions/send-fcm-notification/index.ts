import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.57.0";

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function b64u(v: Uint8Array | string) {
  const a = typeof v === "string" ? new TextEncoder().encode(v) : v;
  let s = "";
  for (const b of a) s += String.fromCharCode(b);
  return btoa(s).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function roleFromGatewayVerifiedJwt(h: string | null): string | null {
  if (!h) return null;
  const m = h.match(/^Bearer\s+(.+)$/i);
  if (!m) return null;
  const p = m[1].split(".");
  if (p.length !== 3) return null;
  try {
    const b = p[1].replace(/-/g, "+").replace(/_/g, "/");
    const c = JSON.parse(atob(b + "=".repeat((4 - (b.length % 4)) % 4)));
    return typeof c.role === "string" ? c.role : null;
  } catch {
    return null;
  }
}

function pemBytes(p: string) {
  let value = p.trim();
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    value = value.slice(1, -1);
  }
  value = value.replace(/\\r/g, "\r").replace(/\\n/g, "\n");
  const match = value.match(
    /-----BEGIN PRIVATE KEY-----([\s\S]*?)-----END PRIVATE KEY-----/
  );
  if (!match) throw new Error("FCM private key must be a PKCS8 PRIVATE KEY PEM");
  const b64 = match[1].replace(/\s/g, "");
  if (!b64 || !/^[A-Za-z0-9+/]+={0,2}$/.test(b64) || b64.length % 4 === 1) {
    throw new Error("FCM private key PEM body is not valid base64");
  }
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

async function googleToken(email: string, pem: string) {
  const n = Math.floor(Date.now() / 1000);
  const u =
    b64u(JSON.stringify({ alg: "RS256", typ: "JWT" })) +
    "." +
    b64u(
      JSON.stringify({
        iss: email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: "https://oauth2.googleapis.com/token",
        iat: n,
        exp: n + 3600,
      })
    );

  const k = await crypto.subtle.importKey(
    "pkcs8",
    pemBytes(pem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const sig = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", k, new TextEncoder().encode(u))
  );

  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: u + "." + b64u(sig),
    }),
  });

  if (!r.ok) {
    console.error("Google OAuth token request failed with HTTP status:", r.status);
    throw new Error("Google OAuth token exchange failed");
  }

  const d = await r.json();
  if (!d.access_token) throw new Error("Google OAuth returned no access token");
  return d.access_token as string;
}

function channel(t: string): string {
  const x = (t ?? "").toUpperCase().trim();
  if (
    x.includes("BOOKING") ||
    x.includes("HOLD") ||
    x.includes("RESERVATION") ||
    x.includes("ORDER")
  ) {
    return "ammal_channel_bookings";
  }
  if (
    x.includes("ADMIN") ||
    x.includes("FARM") ||
    x.includes("GOAT") ||
    x.includes("MODERATION") ||
    x.includes("REPORT") ||
    x.includes("PAYMENT") ||
    x.includes("APPLICATION")
  ) {
    return "ammal_channel_admin";
  }
  return "ammal_channel_general";
}

function deadToken(status: number, text: string, j: any): boolean {
  const code = j?.error?.details?.find((d: any) => d?.errorCode)?.errorCode ?? "";
  const st = j?.error?.status ?? "";
  if (code === "UNREGISTERED" || (status === 404 && st === "NOT_FOUND")) {
    return true;
  }
  const l = text.toLowerCase();
  if (
    l.includes("registration-token-not-registered") ||
    l.includes("the registration token is not a valid fcm registration token") ||
    l.includes("invalid registration token")
  ) {
    return true;
  }
  if (code === "INVALID_ARGUMENT" || st === "INVALID_ARGUMENT") {
    const vs = (j?.error?.details ?? []).flatMap(
      (d: any) => d?.fieldViolations ?? []
    );
    return vs.some(
      (v: any) =>
        v?.field === "message.token" ||
        String(v?.description ?? "").toLowerCase().includes("registration token")
    );
  }
  return false;
}

const maskToken = (t: string) =>
  t.length > 10 ? `${t.slice(0, 6)}...${t.slice(-4)}` : "***";

const maskId = (id: string) =>
  id.length > 8 ? `${id.slice(0, 8)}...` : id;

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "POST required" }, 405);

  // Authorization: accept verified service_role JWT or trusted X-Webhook-Secret
  const expectedSecret = Deno.env.get("FCM_WEBHOOK_SECRET");
  const providedSecret = req.headers.get("x-webhook-secret");
  const role = roleFromGatewayVerifiedJwt(req.headers.get("authorization"));

  const isAuthorized =
    role === "service_role" ||
    (Boolean(expectedSecret) && providedSecret === expectedSecret);

  if (!isAuthorized) {
    console.warn(
      `[FCM] Unauthorized dispatch attempt. Role: ${role ?? "none"}, Secret match: ${Boolean(expectedSecret && providedSecret === expectedSecret)}`
    );
    return json({ error: "Unauthorized or Forbidden" }, 403);
  }

  const url = Deno.env.get("SUPABASE_URL");
  const sk = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const pid =
    Deno.env.get("FCM_PROJECT_ID") ??
    Deno.env.get("FIREBASE_PROJECT_ID") ??
    "ammal-farm";
  const email =
    Deno.env.get("FCM_CLIENT_EMAIL") ?? Deno.env.get("FIREBASE_CLIENT_EMAIL");
  const pk =
    Deno.env.get("FCM_PRIVATE_KEY") ?? Deno.env.get("FIREBASE_PRIVATE_KEY");

  if (!url || !sk || !pid || !email || !pk) {
    console.error(
      "[FCM] Missing required Supabase or Firebase FCM configuration secrets in Edge Function environment"
    );
    return json({ error: "Server configuration incomplete" }, 500);
  }

  let p: any;
  try {
    p = await req.json();
  } catch {
    return json({ error: "Invalid JSON" }, 400);
  }

  if (
    p?.type !== "INSERT" ||
    p?.schema !== "public" ||
    p?.table !== "notifications" ||
    !p?.record
  ) {
    return json({
      ignored: true,
      reason: "Expected public.notifications INSERT webhook payload",
    });
  }

  const row = p.record;
  const uid = typeof row.user_id === "string" ? row.user_id : "";
  const title =
    typeof row.title === "string" ? row.title.slice(0, 150) : "Adu Santhai";
  const body =
    typeof row.body === "string" ? row.body.slice(0, 1000) : "";
  const nid = typeof row.id === "string" ? row.id : "";
  const lt = typeof row.link_type === "string" ? row.link_type : "GENERAL";
  const li = typeof row.link_id === "string" ? row.link_id : "";

  if (!uid || !title || !body || !nid) {
    return json({ error: "Missing required notification fields (user_id, title, body, id)" }, 400);
  }

  // Deduplication guard: check if already sent via FCM
  if (row.fcm_status === "SENT") {
    return json({
      status: "ALREADY_SENT",
      notificationId: nid,
      message: "Notification already dispatched via FCM",
    });
  }

  console.log(
    `[FCM] Processing notification ${maskId(nid)} for recipient ${maskId(uid)}, type: ${lt}`
  );

  const db = createClient(url, sk, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data: rows, error } = await db
    .from("fcm_device_tokens")
    .select("token")
    .eq("user_id", uid);

  if (error) {
    console.error(`[FCM] Failed to query device tokens for recipient ${maskId(uid)}:`, error.message);
    return json({ error: "Failed to query device tokens" }, 500);
  }

  const tokens = [
    ...new Set(
      (rows ?? [])
        .map((r: any) => String(r.token ?? "").trim())
        .filter(Boolean)
    ),
  ];

  // Distinguish unavailable-device case from successful delivery
  if (!tokens.length) {
    console.log(`[FCM] No registered active FCM device tokens found for recipient ${maskId(uid)}`);
    try {
      await db
        .from("notifications")
        .update({
          fcm_status: "NO_TOKENS",
          fcm_sent_at: new Date().toISOString(),
        })
        .eq("id", nid);
    } catch (_e) {
      // Non-fatal
    }

    return json(
      {
        status: "NO_ACTIVE_DEVICES",
        notificationId: nid,
        recipientUserId: uid,
        totalTokens: 0,
        sentCount: 0,
        failedCount: 0,
        message: "No registered device tokens for recipient",
      },
      200
    );
  }

  let access: string;
  try {
    access = await googleToken(email, pk);
  } catch (e) {
    console.error(
      "[FCM] Google OAuth authentication failed:",
      e instanceof Error ? e.message : "Unknown error"
    );
    return json({ error: "FCM authentication failed" }, 502);
  }

  let sent = 0;
  let failed = 0;
  const invalid: string[] = [];
  const endpoint = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(
    pid
  )}/messages:send`;

  const targetChannel = channel(lt);

  for (const token of tokens) {
    const msg = {
      message: {
        token,
        notification: {
          title,
          body,
        },
        data: {
          notification_id: nid,
          title,
          body,
          message: body,
          recipient_user_id: uid,
          link_type: lt,
          link_id: li,
          reference_id: li,
        },
        android: {
          priority: "HIGH",
          notification: {
            channel_id: targetChannel,
            icon: "ic_notification_small",
            color: "#2E7D32",
            default_sound: true,
            default_vibrate_timings: true,
            visibility: "PUBLIC",
            tag: nid,
          },
        },
      },
    };

    try {
      const r = await fetch(endpoint, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${access}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(msg),
      });

      if (r.ok) {
        sent++;
        console.log(`[FCM] Successfully delivered message to token ${maskToken(token)}`);
      } else {
        failed++;
        const text = await r.text();
        let j: any = null;
        try {
          j = JSON.parse(text);
        } catch {}

        console.error(
          `[FCM] Send failed for token ${maskToken(token)}: HTTP ${r.status}`,
          j?.error?.status ?? "FCM_ERROR"
        );

        if (deadToken(r.status, text, j)) {
          invalid.push(token);
        }
      }
    } catch (e) {
      failed++;
      console.error(
        `[FCM] Network request failed for token ${maskToken(token)}:`,
        e instanceof Error ? e.message : "Network error"
      );
    }
  }

  // Prune invalid tokens automatically
  if (invalid.length) {
    const { error: e } = await db
      .from("fcm_device_tokens")
      .delete()
      .in("token", invalid);

    if (e) {
      console.error("[FCM] Invalid-token cleanup notice:", e.message);
    } else {
      console.log(`[FCM] Cleaned ${invalid.length} expired or invalid FCM device tokens`);
    }
  }

  // Update notification delivery status for duplicate prevention
  const deliveryStatus =
    sent > 0 && failed === 0
      ? "SENT"
      : sent > 0
      ? "PARTIALLY_SENT"
      : "FAILED";

  try {
    await db
      .from("notifications")
      .update({
        fcm_status: deliveryStatus,
        fcm_sent_at: new Date().toISOString(),
      })
      .eq("id", nid);
  } catch (_e) {
    // Non-fatal if column not yet added
  }

  const responseStatus =
    sent > 0 && failed === 0
      ? "DELIVERED"
      : sent > 0
      ? "PARTIALLY_DELIVERED"
      : "FAILED";

  const httpCode = sent > 0 ? 200 : 502;

  console.log(
    `[FCM] Finished notification ${maskId(nid)}: status=${responseStatus}, sent=${sent}, failed=${failed}, totalTokens=${tokens.length}`
  );

  return json(
    {
      status: responseStatus,
      notificationId: nid,
      totalTokens: tokens.length,
      sentCount: sent,
      failedCount: failed,
      cleanedInvalidTokens: invalid.length,
    },
    httpCode
  );
});
