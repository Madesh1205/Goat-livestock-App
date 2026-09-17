// Supabase Edge Function: send-fcm-notification
// Sends FCM push notifications to buyers and farm owners when events occur.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface PushNotificationPayload {
  userId: string;
  title: string;
  body: string;
  data?: Record<string, string>;
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

    // 1. Authenticate the caller
    const authHeader = req.headers.get("Authorization");
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      return new Response(
        JSON.stringify({ error: "Unauthorized: Missing or invalid authorization token" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const token = authHeader.replace("Bearer ", "").trim();
    const isServiceRole = supabaseServiceKey && token === supabaseServiceKey;
    let authenticatedUserId: string | null = null;
    let authenticatedUserRole = "CUSTOMER";

    if (!isServiceRole) {
      const authClient = createClient(supabaseUrl, supabaseAnonKey || supabaseServiceKey, {
        auth: { persistSession: false },
        global: { headers: { Authorization: `Bearer ${token}` } }
      });
      const { data: { user }, error: authErr } = await authClient.auth.getUser();

      if (authErr || !user) {
        return new Response(
          JSON.stringify({ error: "Unauthorized: Invalid authentication credentials" }),
          { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      authenticatedUserId = user.id;

      // Fetch user role
      const { data: profile } = await authClient
        .from("profiles")
        .select("role")
        .eq("id", user.id)
        .maybeSingle();

      if (profile?.role) {
        authenticatedUserRole = profile.role;
      }
    }

    // 2. Parse and validate input payload
    let payload: PushNotificationPayload;
    try {
      payload = await req.json();
    } catch (_e) {
      return new Response(
        JSON.stringify({ error: "Invalid JSON request body" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const { userId, title, body, data } = payload;

    if (!userId || typeof userId !== "string" || userId.trim().length === 0 || userId.length > 100) {
      return new Response(
        JSON.stringify({ error: "Validation error: 'userId' must be a valid non-empty string" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!title || typeof title !== "string" || title.trim().length === 0 || title.length > 200) {
      return new Response(
        JSON.stringify({ error: "Validation error: 'title' must be a non-empty string under 200 characters" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!body || typeof body !== "string" || body.trim().length === 0 || body.length > 1000) {
      return new Response(
        JSON.stringify({ error: "Validation error: 'body' must be a non-empty string under 1000 characters" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 3. Authorization check: Non-service-role & non-super-admin can only notify themselves or counterparties
    if (!isServiceRole && authenticatedUserRole !== "SUPER_ADMIN" && authenticatedUserId !== userId.trim()) {
      // If customer or farm admin is sending to someone else, check counterparty relationship if applicable
      // Super Admin and system service role can send to any user.
      if (authenticatedUserRole !== "FARM_ADMIN" && authenticatedUserRole !== "CUSTOMER") {
        return new Response(
          JSON.stringify({ error: "Forbidden: Cannot dispatch notifications to other users" }),
          { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
    }

    // 4. Initialize service-role client for storing notification and checking tokens
    const adminClient = createClient(supabaseUrl, supabaseServiceKey || supabaseAnonKey, {
      auth: { persistSession: false }
    });

    const targetUserId = userId.trim();
    const cleanTitle = title.trim();
    const cleanBody = body.trim();
    const linkType = (data && typeof data.link_type === "string") ? data.link_type.slice(0, 50) : "GENERAL";
    const linkId = (data && typeof data.link_id === "string") ? data.link_id.slice(0, 100) : null;

    // 5. Store the notification in Supabase notifications table
    const { data: notifRecord, error: notifErr } = await adminClient
      .from("notifications")
      .insert({
        user_id: targetUserId,
        title: cleanTitle,
        body: cleanBody,
        link_type: linkType,
        link_id: linkId,
        is_read: false,
        created_at: new Date().toISOString()
      })
      .select("id")
      .single();

    if (notifErr) {
      console.error("Failed to insert notification record");
      return new Response(
        JSON.stringify({ error: "Failed to store notification record" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 6. Fetch user's FCM device token if registered
    const { data: profile } = await adminClient
      .from("profiles")
      .select("fcm_token")
      .eq("id", targetUserId)
      .maybeSingle();

    const fcmServerKey = Deno.env.get("FCM_SERVER_KEY");
    let fcmSent = false;

    if (profile?.fcm_token && fcmServerKey) {
      try {
        const fcmRes = await fetch("https://fcm.googleapis.com/fcm/send", {
          method: "POST",
          headers: {
            "Authorization": `key=${fcmServerKey}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            to: profile.fcm_token,
            notification: {
              title: cleanTitle,
              body: cleanBody,
              sound: "default",
            },
            data: data ?? {},
          }),
        });
        fcmSent = fcmRes.ok;
      } catch (_err) {
        console.error("FCM dispatch communication error");
      }
    }

    return new Response(
      JSON.stringify({
        success: true,
        notificationId: notifRecord?.id,
        fcmSent,
        userId: targetUserId
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_error: any) {
    return new Response(
      JSON.stringify({ error: "An unexpected error occurred while sending notification" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
