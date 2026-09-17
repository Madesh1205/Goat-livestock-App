// Supabase Edge Function: expire-bookings
// Automatically releases 48-hour holds for expired booking reservations.
// Transitions booking status to EXPIRED and restores goat availability to AVAILABLE.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";

    // 1. Authenticate the caller (Bearer token must be either service role, cron secret, or authenticated Super Admin)
    const authHeader = req.headers.get("Authorization");
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      return new Response(
        JSON.stringify({ error: "Unauthorized: Missing or invalid authorization token" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const token = authHeader.replace("Bearer ", "").trim();
    const isServiceRole = supabaseServiceKey && token === supabaseServiceKey;

    if (!isServiceRole) {
      // Validate user token using anon client
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

      // Check if user is SUPER_ADMIN
      const { data: profile } = await authClient
        .from("profiles")
        .select("role")
        .eq("id", user.id)
        .maybeSingle();

      if (!profile || profile.role !== "SUPER_ADMIN") {
        return new Response(
          JSON.stringify({ error: "Forbidden: Super Admin or service role privileges required" }),
          { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
    }

    // 2. Initialize service-role client for background hold release
    const adminClient = createClient(supabaseUrl, supabaseServiceKey || supabaseAnonKey, {
      auth: { persistSession: false }
    });

    const nowIso = new Date().toISOString();

    // 3. Fetch expired bookings in PENDING or HOLD state whose hold_expires_at is in the past
    const { data: expiredBookings, error: fetchErr } = await adminClient
      .from("bookings")
      .select("id, goat_id, customer_id, farm_id, booking_code, hold_expires_at")
      .or("status.eq.PENDING,status.eq.HOLD")
      .lt("hold_expires_at", nowIso);

    if (fetchErr) {
      console.error("Error fetching expired bookings");
      return new Response(
        JSON.stringify({ error: "Failed to query expired bookings" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!expiredBookings || expiredBookings.length === 0) {
      return new Response(
        JSON.stringify({
          success: true,
          message: "No expired booking holds at this time.",
          releasedCount: 0,
          checkedAt: nowIso
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const bookingIds = expiredBookings.map((b) => b.id);
    const goatIds = expiredBookings.map((b) => b.goat_id).filter(Boolean);

    // 4. Mark bookings as EXPIRED
    const { error: updateBookingErr } = await adminClient
      .from("bookings")
      .update({ status: "EXPIRED" })
      .in("id", bookingIds);

    if (updateBookingErr) {
      console.error("Error updating expired bookings status");
      return new Response(
        JSON.stringify({ error: "Failed to update expired bookings status" }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 5. Set goat status back to AVAILABLE
    if (goatIds.length > 0) {
      await adminClient
        .from("goats")
        .update({ status: "AVAILABLE" })
        .in("id", goatIds);
    }

    // 6. Notify customers about the expired hold
    for (const booking of expiredBookings) {
      if (booking.customer_id) {
        await adminClient.from("notifications").insert({
          user_id: booking.customer_id,
          title: "Hold Expired ⏳",
          body: `Your 48-hour reservation hold for booking #${booking.booking_code || booking.id.slice(0, 6)} has expired. The goat is now available for other buyers.`,
          link_type: "MY_BOOKINGS",
          link_id: booking.id,
          is_read: false
        });
      }
    }

    return new Response(
      JSON.stringify({
        success: true,
        message: `Successfully released ${expiredBookings.length} expired reservation holds.`,
        releasedCount: expiredBookings.length,
        bookingIds,
        goatIds,
        timestamp: nowIso
      }),
      { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_err) {
    return new Response(
      JSON.stringify({ error: "An unexpected error occurred while processing expired bookings." }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
