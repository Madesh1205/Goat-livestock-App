// Supabase Edge Function: send-fcm-notification
// Dispatches real-time Firebase Cloud Messaging (FCM) v1 push notifications on notification inserts.
// Invoked by Supabase database webhooks with verified service_role credentials.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface GoogleTokenCache {
  token: string;
  expiresAt: number;
}

let cachedGoogleToken: GoogleTokenCache | null = null;

function base64UrlEncode(input: Uint8Array | string): string {
  const binary = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let str = "";
  for (let i = 0; i < binary.length; i++) {
    str += String.fromCharCode(binary[i]);
  }
  return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Validates that the caller holds the Supabase service_role.
 *
 * NOTE: This authorization check relies explicitly on Supabase Gateway JWT signature
 * verification being enabled on the platform (verify_jwt = true). The Gateway cryptographically
 * validates the signature and authenticity of the JWT against the project secret before invocation.
 *
 * Here, we inspect the token to ensure missing authorization or non-service-role callers
 * (such as anon or regular authenticated user roles) are rejected, while accepting valid
 * service_role credentials (either literal key match or verified service_role claims).
 */
function isServiceRoleCaller(authHeader: string | null, serviceRoleKey: string): boolean {
  if (!authHeader) return false;

  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) return false;

  // 1. Literal match against configured service_role key
  if (serviceRoleKey && token === serviceRoleKey.trim()) {
    return true;
  }

  // 2. Decode claims from the Gateway-verified JWT and verify role === "service_role"
  try {
    const parts = token.split(".");
    if (parts.length >= 2) {
      const payloadBase64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
      const pad = (4 - (payloadBase64.length % 4)) % 4;
      const paddedBase64 = payloadBase64 + "=".repeat(pad);
      const decodedJson = decodeURIComponent(
        atob(paddedBase64)
          .split("")
          .map((c) => "%" + ("00" + c.charCodeAt(0).toString(16)).slice(-2))
          .join("")
      );
      const claims = JSON.parse(decodedJson);
      if (claims.role === "service_role") {
        return true;
      }
    }
  } catch (_e) {
    // Malformed token claims
  }

  return false;
}

/**
 * Obtains an OAuth2 access token for Google FCM HTTP v1 using the service account RSA private key.
 */
async function getGoogleAccessToken(
  clientEmail: string,
  rawPrivateKey: string
): Promise<string> {
  const now = Math.floor(Date.now() / 1000);

  // Return cached token if valid for at least 5 more minutes
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > now + 300) {
    return cachedGoogleToken.token;
  }

  // Strip PEM headers and whitespace to get binary PKCS8 bytes
  const cleanKey = rawPrivateKey
    .replace(/\\n/g, "\n")
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");

  const binaryDer = Uint8Array.from(atob(cleanKey), (c) => c.charCodeAt(0));

  const importedKey = await crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    false,
    ["sign"]
  );

  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now,
  };

  const unsignedToken = `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(JSON.stringify(payload))}`;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    importedKey,
    new TextEncoder().encode(unsignedToken)
  );

  const assertion = `${unsignedToken}.${base64UrlEncode(new Uint8Array(signature))}`;

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: assertion,
    }),
  });

  if (!tokenResponse.ok) {
    const errorBody = await tokenResponse.text();
    throw new Error(`Google OAuth2 token exchange failed (${tokenResponse.status}): ${errorBody}`);
  }

  const tokenData = await tokenResponse.json();
  const accessToken = tokenData.access_token as string;
  const expiresIn = (tokenData.expires_in as number) || 3600;

  cachedGoogleToken = {
    token: accessToken,
    expiresAt: now + expiresIn,
  };

  return accessToken;
}

/**
 * Maps notification category / type to appropriate Android notification channel ID.
 */
function resolveChannelId(typeOrLinkType: string): string {
  const normalized = (typeOrLinkType || "").toUpperCase();
  if (normalized.includes("BOOKING") || normalized.includes("HOLD") || normalized.includes("RESERVATION")) {
    return "ammal_channel_bookings";
  }
  if (normalized.includes("ADMIN") || normalized.includes("FARM_APPLICATION") || normalized.includes("PAYMENT")) {
    return "ammal_channel_admin";
  }
  return "ammal_channel_general";
}

/**
 * Determines if an FCM HTTP v1 error specifically confirms that the device registration token
 * is invalid, expired, or unregistered.
 *
 * Does NOT delete device tokens solely for generic INVALID_ARGUMENT errors (which can occur
 * due to payload formatting, invalid notification parameters, etc.). It only flags the token
 * when the FCM response explicitly confirms the registration token itself is unregistered or invalid.
 */
function isTokenUnregisteredOrInvalid(
  httpStatus: number,
  errText: string,
  errJson: any
): boolean {
  const errorCode = errJson?.error?.details?.[0]?.errorCode || "";
  const errorStatus = errJson?.error?.status || "";

  // 1. UNREGISTERED specifically confirms the token was uninstalled or expired
  if (errorCode === "UNREGISTERED") {
    return true;
  }

  // 2. HTTP 404 with NOT_FOUND / UNREGISTERED indicates the token entity does not exist in FCM
  if (httpStatus === 404 && (errorStatus === "NOT_FOUND" || errorCode === "UNREGISTERED")) {
    return true;
  }

  // 3. Explicit error description strings confirming token invalidity
  const lowerErrText = errText.toLowerCase();
  if (
    lowerErrText.includes("registration-token-not-registered") ||
    lowerErrText.includes("the registration token is not a valid fcm registration token") ||
    lowerErrText.includes("invalid registration token")
  ) {
    return true;
  }

  // 4. For INVALID_ARGUMENT, only delete if the field violation specifically targets "message.token"
  if (errorCode === "INVALID_ARGUMENT" || errorStatus === "INVALID_ARGUMENT") {
    const details = Array.isArray(errJson?.error?.details) ? errJson.error.details : [];
    for (const d of details) {
      if (Array.isArray(d.fieldViolations)) {
        for (const fv of d.fieldViolations) {
          if (fv.field === "message.token" || fv.description?.toLowerCase().includes("registration token")) {
            return true;
          }
        }
      }
    }
  }

  return false;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    // Preserve existing live secret names (FCM_*), falling back to FIREBASE_* if needed
    const fcmProjectId =
      Deno.env.get("FCM_PROJECT_ID") ??
      Deno.env.get("FIREBASE_PROJECT_ID") ??
      "ammal-farm";

    const fcmClientEmail =
      Deno.env.get("FCM_CLIENT_EMAIL") ??
      Deno.env.get("FIREBASE_CLIENT_EMAIL") ??
      "";

    const fcmPrivateKey =
      Deno.env.get("FCM_PRIVATE_KEY") ??
      Deno.env.get("FIREBASE_PRIVATE_KEY") ??
      "";

    // 1. Authenticate caller: Gateway JWT verification is active, and caller must be service_role
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(
        JSON.stringify({ error: "Unauthorized: Missing authorization header" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!isServiceRoleCaller(authHeader, supabaseServiceKey)) {
      return new Response(
        JSON.stringify({ error: "Forbidden" }),
        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Parse incoming webhook or direct payload
    const body = await req.json().catch(() => ({}));
    const record = body.record ?? body;

    const userId = record.user_id ?? record.recipient_user_id ?? record.userId;
    if (!userId) {
      return new Response(
        JSON.stringify({ success: false, message: "Missing user_id / recipient_user_id in payload" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const title = record.title ?? "Adu Santhai Update";
    const message = record.body ?? record.message ?? "";
    const notificationId = record.id ?? record.notification_id ?? crypto.randomUUID();
    const linkType = record.link_type ?? record.linkType ?? "";
    const linkId = record.link_id ?? record.linkId ?? "";
    const deepLinkRoute = record.deep_link_route ?? record.deepLinkRoute ?? (linkType ? `orders` : "");
    const channelId = resolveChannelId(linkType);

    // 3. Initialize Supabase service-role client for FCM token lookup & invalid token cleanup
    const adminClient = createClient(supabaseUrl, supabaseServiceKey, {
      auth: { persistSession: false },
    });

    // Lookup device tokens for recipient user
    const { data: tokenRows, error: tokenError } = await adminClient
      .from("fcm_device_tokens")
      .select("token")
      .eq("user_id", userId);

    if (tokenError) {
      console.error(`Failed to lookup FCM device tokens for user: ${tokenError.message}`);
      return new Response(
        JSON.stringify({ error: "Failed to query device tokens" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!tokenRows || tokenRows.length === 0) {
      return new Response(
        JSON.stringify({
          success: true,
          message: "No registered FCM device tokens found for user",
          sentCount: 0,
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Deduplicate tokens
    const uniqueTokens = Array.from(
      new Set(
        tokenRows
          .map((r: { token?: string }) => r.token?.trim())
          .filter((t): t is string => Boolean(t && t.length > 0))
      )
    );

    if (uniqueTokens.length === 0) {
      return new Response(
        JSON.stringify({ success: true, message: "No valid tokens to send", sentCount: 0 }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 4. Obtain Google OAuth2 access token using live FCM service account secrets
    if (!fcmClientEmail || !fcmPrivateKey) {
      console.warn("FCM service account credentials missing in Edge Function secrets.");
      return new Response(
        JSON.stringify({
          success: false,
          error: "FCM service account credentials (FCM_CLIENT_EMAIL / FCM_PRIVATE_KEY) not configured",
        }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const googleAccessToken = await getGoogleAccessToken(fcmClientEmail, fcmPrivateKey);
    const fcmEndpoint = `https://fcm.googleapis.com/v1/projects/${fcmProjectId}/messages:send`;

    let successCount = 0;
    let failureCount = 0;
    const invalidTokens: string[] = [];

    // 5. Send FCM HTTP v1 notifications to each token
    for (const token of uniqueTokens) {
      const fcmPayload = {
        message: {
          token: token,
          notification: {
            title: title,
            body: message,
          },
          data: {
            notification_id: String(notificationId),
            title: String(title),
            message: String(message),
            recipient_user_id: String(userId),
            link_type: String(linkType),
            reference_id: String(linkId),
            deep_link_route: String(deepLinkRoute),
          },
          android: {
            priority: "HIGH",
            notification: {
              channel_id: channelId,
              notification_count: 0,
            },
          },
        },
      };

      try {
        const fcmResponse = await fetch(fcmEndpoint, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${googleAccessToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(fcmPayload),
        });

        if (fcmResponse.ok) {
          successCount++;
        } else {
          failureCount++;
          const errText = await fcmResponse.text();
          let errJson: any = null;
          try {
            errJson = JSON.parse(errText);
          } catch (_) {}

          // Check specifically if the device registration token itself is confirmed dead/unregistered
          if (isTokenUnregisteredOrInvalid(fcmResponse.status, errText, errJson)) {
            invalidTokens.push(token);
          }
        }
      } catch (sendErr) {
        failureCount++;
        console.error(`FCM message dispatch error: ${(sendErr as Error).message}`);
      }
    }

    // 6. Clean up only specifically confirmed invalid/unregistered tokens from fcm_device_tokens
    if (invalidTokens.length > 0) {
      try {
        await adminClient
          .from("fcm_device_tokens")
          .delete()
          .in("token", invalidTokens);
      } catch (cleanupErr) {
        console.error(`Failed to clean up stale FCM tokens: ${(cleanupErr as Error).message}`);
      }
    }

    return new Response(
      JSON.stringify({
        success: true,
        notificationId,
        recipientUserId: userId,
        totalTokens: uniqueTokens.length,
        sentCount: successCount,
        failedCount: failureCount,
        cleanedInvalidTokens: invalidTokens.length,
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error(`Error in send-fcm-notification: ${(error as Error).message}`);
    return new Response(
      JSON.stringify({ error: "Internal server error processing push notification" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
