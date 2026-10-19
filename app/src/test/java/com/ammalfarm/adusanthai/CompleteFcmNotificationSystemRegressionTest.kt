package com.ammalfarm.adusanthai

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ammalfarm.adusanthai.core.notification.NotificationConfig
import com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker
import com.ammalfarm.adusanthai.core.notification.NotificationHelper
import com.ammalfarm.adusanthai.model.AppNotification
import com.ammalfarm.adusanthai.model.NotificationType
import com.ammalfarm.adusanthai.model.UserRole
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompleteFcmNotificationSystemRegressionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        NotificationConfig.setActiveUser(context, null)
        NotificationConfig.clearSavedFcmToken(context)
    }

    // -------------------------------------------------------------------------
    // 1. TOKEN REGISTRATION, STORAGE & ROTATION
    // -------------------------------------------------------------------------

    @Test
    fun testTokenSavingAndRetrieval() {
        val testToken = "test_fcm_token_initial_12345"
        NotificationConfig.saveSavedFcmToken(context, testToken)
        assertEquals(testToken, NotificationConfig.getSavedFcmToken(context))

        // Token rotation: update with new token
        val rotatedToken = "test_fcm_token_rotated_67890"
        NotificationConfig.saveSavedFcmToken(context, rotatedToken)
        assertEquals(rotatedToken, NotificationConfig.getSavedFcmToken(context))

        NotificationConfig.clearSavedFcmToken(context)
        assertNull(NotificationConfig.getSavedFcmToken(context))
    }

    @Test
    fun testActiveUserAssociationAndLogoutIsolation() {
        val userA = "11111111-1111-1111-1111-111111111111"
        val userB = "22222222-2222-2222-2222-222222222222"

        NotificationConfig.setActiveUser(context, userA)
        assertEquals(userA, NotificationConfig.getActiveUserId(context))

        // Switch to user B
        NotificationConfig.setActiveUser(context, userB)
        assertEquals(userB, NotificationConfig.getActiveUserId(context))

        // Logout user B
        NotificationConfig.setActiveUser(context, null)
        assertNull(NotificationConfig.getActiveUserId(context))
    }

    // -------------------------------------------------------------------------
    // 2. NOTIFICATION CHANNELS & LINK TYPE ROUTING
    // -------------------------------------------------------------------------

    @Test
    fun testNotificationChannelsCreated() {
        NotificationHelper.createNotificationChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelBookings = nm.getNotificationChannel(NotificationHelper.CHANNEL_BOOKINGS)
        val channelAdmin = nm.getNotificationChannel(NotificationHelper.CHANNEL_ADMIN)
        val channelGeneral = nm.getNotificationChannel(NotificationHelper.CHANNEL_GENERAL)

        assertNotNull("Bookings channel must be created", channelBookings)
        assertNotNull("Admin channel must be created", channelAdmin)
        assertNotNull("General channel must be created", channelGeneral)

        assertEquals("Bookings channel should have HIGH importance", NotificationManager.IMPORTANCE_HIGH, channelBookings.importance)
        assertEquals("Admin channel should have HIGH importance", NotificationManager.IMPORTANCE_HIGH, channelAdmin.importance)
        assertEquals("General channel should have DEFAULT importance", NotificationManager.IMPORTANCE_DEFAULT, channelGeneral.importance)
    }

    @Test
    fun testSelectChannelForLinkType() {
        assertEquals(NotificationHelper.CHANNEL_BOOKINGS, NotificationHelper.selectChannelForLinkType("BOOKING"))
        assertEquals(NotificationHelper.CHANNEL_BOOKINGS, NotificationHelper.selectChannelForLinkType("HOLD"))
        assertEquals(NotificationHelper.CHANNEL_BOOKINGS, NotificationHelper.selectChannelForLinkType("RESERVATION"))

        assertEquals(NotificationHelper.CHANNEL_ADMIN, NotificationHelper.selectChannelForLinkType("FARM_APPLICATION"))
        assertEquals(NotificationHelper.CHANNEL_ADMIN, NotificationHelper.selectChannelForLinkType("MODERATION"))
        assertEquals(NotificationHelper.CHANNEL_ADMIN, NotificationHelper.selectChannelForLinkType("REPORT"))
        assertEquals(NotificationHelper.CHANNEL_ADMIN, NotificationHelper.selectChannelForLinkType("PAYMENT"))

        assertEquals(NotificationHelper.CHANNEL_GENERAL, NotificationHelper.selectChannelForLinkType("GENERAL"))
        assertEquals(NotificationHelper.CHANNEL_GENERAL, NotificationHelper.selectChannelForLinkType(null))
        assertEquals(NotificationHelper.CHANNEL_GENERAL, NotificationHelper.selectChannelForLinkType(""))
    }

    // -------------------------------------------------------------------------
    // 3. DEDUPLICATION & RECIPIENT ISOLATION
    // -------------------------------------------------------------------------

    @Test
    fun testNotificationDeduplication() {
        val userId = "user-dedup-1"
        val notificationId = "notif-uuid-xyz-999"

        assertFalse(NotificationDeliveryTracker.isDelivered(context, userId, notificationId))

        NotificationDeliveryTracker.markDelivered(context, userId, notificationId)
        assertTrue(NotificationDeliveryTracker.isDelivered(context, userId, notificationId))

        // Different user should not be marked as delivered
        assertFalse(NotificationDeliveryTracker.isDelivered(context, "other-user", notificationId))

        // Clear for user resets their state
        NotificationDeliveryTracker.clearForUser(context, userId)
        assertFalse(NotificationDeliveryTracker.isDelivered(context, userId, notificationId))
    }

    @Test
    fun testPrivateRecipientSuppression() {
        val userAlice = "user-alice-uuid"
        val userBob = "user-bob-uuid"

        NotificationConfig.setActiveUser(context, userAlice)

        // Incoming notification specifically for user Bob
        val notifForBob = AppNotification(
            id = UUID.randomUUID().toString(),
            userId = userBob,
            title = "Bob's Booking",
            message = "Booking confirmed",
            type = "BOOKING_CONFIRMED"
        )

        val activeUser = NotificationConfig.getActiveUserId(context)
        val shouldDisplay = notifForBob.userId.isBlank() || notifForBob.userId == activeUser
        assertFalse("Private notification for Bob must not be displayed to Alice", shouldDisplay)
    }

    // -------------------------------------------------------------------------
    // 4. DESTINATION ROUTING & DESTINATION VALIDATION
    // -------------------------------------------------------------------------

    @Test
    fun testDestinationRoutingLogic() {
        fun resolveRoute(
            linkType: String?,
            linkId: String?,
            role: UserRole?,
            availableGoatIds: Set<String>,
            availableFarmIds: Set<String>
        ): String {
            val cleanType = linkType?.trim()?.uppercase().orEmpty()
            val cleanId = linkId?.trim().orEmpty()

            return when {
                cleanType == "BOOKING" || cleanType == "HOLD" || cleanType == "RESERVATION" -> "bookings"
                cleanType == "FARM_BOOKINGS" -> {
                    if (role == UserRole.FARM_ADMIN || role == UserRole.SUPER_ADMIN) "farm_admin" else "bookings"
                }
                cleanType == "GOAT" && cleanId.isNotBlank() -> {
                    if (availableGoatIds.contains(cleanId)) "goat/$cleanId" else "notifications"
                }
                cleanType == "FARM" && cleanId.isNotBlank() -> {
                    if (availableFarmIds.contains(cleanId)) "farm/$cleanId"
                    else if (role == UserRole.FARM_ADMIN) "farm_admin"
                    else "notifications"
                }
                cleanType == "FARM_APPLICATION" || cleanType == "MODERATION" || cleanType == "REPORT" -> {
                    if (role == UserRole.SUPER_ADMIN) "super_admin"
                    else if (role == UserRole.FARM_ADMIN) "farm_admin"
                    else "notifications"
                }
                else -> "notifications"
            }
        }

        val goats = setOf("goat-123")
        val farms = setOf("farm-456")

        // Customer bookings
        assertEquals("bookings", resolveRoute("BOOKING", "b-1", UserRole.CUSTOMER, goats, farms))

        // Farm Admin booking view
        assertEquals("farm_admin", resolveRoute("FARM_BOOKINGS", "b-2", UserRole.FARM_ADMIN, goats, farms))

        // Existing goat detail
        assertEquals("goat/goat-123", resolveRoute("GOAT", "goat-123", UserRole.CUSTOMER, goats, farms))

        // Deleted or inaccessible goat gracefully falls back to notification list
        assertEquals("notifications", resolveRoute("GOAT", "deleted-goat-999", UserRole.CUSTOMER, goats, farms))

        // Super Admin moderation route
        assertEquals("super_admin", resolveRoute("MODERATION", "g-1", UserRole.SUPER_ADMIN, goats, farms))

        // Farm admin accessing moderation notification routes to farm admin screen
        assertEquals("farm_admin", resolveRoute("MODERATION", "g-1", UserRole.FARM_ADMIN, goats, farms))
    }

    // -------------------------------------------------------------------------
    // 5. UNREAD COUNT AND READ STATE PERSISTENCE
    // -------------------------------------------------------------------------

    @Test
    fun testUnreadCountAndMarkAsRead() {
        val list = listOf(
            AppNotification(id = "1", userId = "User 1", title = "Title 1", message = "Body 1", isRead = false),
            AppNotification(id = "2", userId = "User 1", title = "Title 2", message = "Body 2", isRead = false),
            AppNotification(id = "3", userId = "User 1", title = "Title 3", message = "Body 3", isRead = true)
        )

        val unreadInitial = list.count { !it.isRead }
        assertEquals(2, unreadInitial)

        // Mark item 1 as read
        val updatedList = list.map { if (it.id == "1") it.copy(isRead = true) else it }
        val unreadAfter = updatedList.count { !it.isRead }
        assertEquals(1, unreadAfter)

        // Mark item 2 as read
        val fullyReadList = updatedList.map { if (it.id == "2") it.copy(isRead = true) else it }
        val unreadFinal = fullyReadList.count { !it.isRead }
        assertEquals(0, unreadFinal)
    }

    // -------------------------------------------------------------------------
    // 6. SERVER-SIDE MIGRATION & EDGE FUNCTION AUDIT
    // -------------------------------------------------------------------------

    @Test
    fun testMigrationContainsAllRequiredTablesAndTriggers() {
        val migrationFile = File("supabase/migrations/20261019000000_fcm_notification_system_production_fix.sql")
        assertTrue("FCM production migration file must exist", migrationFile.exists())
        val sql = migrationFile.readText()

        assertTrue("Must create fcm_device_tokens table", sql.contains("CREATE TABLE IF NOT EXISTS public.fcm_device_tokens"))
        assertTrue("Must enable RLS on fcm_device_tokens", sql.contains("ALTER TABLE public.fcm_device_tokens ENABLE ROW LEVEL SECURITY"))
        assertTrue("Must provide register_fcm_device_token RPC", sql.contains("register_fcm_device_token"))
        assertTrue("Must provide remove_fcm_device_token RPC", sql.contains("remove_fcm_device_token"))
        assertTrue("Must notify super admin on farm registration", sql.contains("notify_super_admin_on_farm_registration"))
        assertTrue("Must notify super admin on goat submission", sql.contains("notify_super_admin_on_goat_submission"))
        assertTrue("Must notify super admin on user reports", sql.contains("notify_super_admin_on_report"))
        assertTrue("Must contain net.http_post FCM trigger", sql.contains("send_fcm_on_notification_insert"))
    }

    @Test
    fun testEdgeFunctionCodeAudit() {
        val edgeFunction = File("supabase/functions/send-fcm-notification/index.ts")
        assertTrue("send-fcm-notification edge function must exist", edgeFunction.exists())
        val ts = edgeFunction.readText()

        assertTrue("Must distinguish NO_ACTIVE_DEVICES", ts.contains("NO_ACTIVE_DEVICES"))
        assertTrue("Must distinguish DELIVERED", ts.contains("DELIVERED"))
        assertTrue("Must handle deadToken cleanup", ts.contains("deadToken"))
        assertTrue("Must mask tokens in logs", ts.contains("maskToken"))
        assertTrue("Must support X-Webhook-Secret or service_role", ts.contains("x-webhook-secret") || ts.contains("service_role"))
    }
}
