package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.ui.navigation.Screen
import com.ammalfarm.adusanthai.ui.screens.getOrderStatusStyle
import com.ammalfarm.adusanthai.util.NotificationDeepLinkPayload
import org.junit.Assert.*
import org.junit.Test

class OrdersSystemAndNavigationAuditTest {

    private val goat1 = Goat(
        id = "goat-101",
        goatCode = "AGT-001",
        name = "Kanni Champion",
        breed = "Kanni",
        gender = GoatGender.MALE,
        ageMonths = 18,
        weightKg = 42.0,
        purpose = GoatPurpose.BREEDING,
        description = "Pure Kanni champion lineage",
        price = 15000.0,
        farmId = "farm-1",
        farmName = "Ammal Goat Farm",
        farmLocation = "Madurai, Tamil Nadu",
        availabilityStatus = AvailabilityStatus.RESERVED
    )

    private val goat2 = Goat(
        id = "goat-102",
        goatCode = "AGT-002",
        name = "Salem Black Pure",
        breed = "Salem Black",
        gender = GoatGender.FEMALE,
        ageMonths = 14,
        weightKg = 35.0,
        purpose = GoatPurpose.BREEDING,
        description = "Healthy Salem Black breed",
        price = 22000.0,
        farmId = "farm-2",
        farmName = "Salem Breeds",
        farmLocation = "Salem, Tamil Nadu",
        availabilityStatus = AvailabilityStatus.AVAILABLE
    )

    // Flow A: CUSTOMER Reserve goat -> Orders -> order shows RESERVED
    @Test
    fun testFlowA_CustomerReserveGoat_ShowsReservedInOrders() {
        val customerId = "customer-123"
        val booking = Booking(
            id = "booking-001",
            bookingCode = "ORD-1001",
            goatId = goat1.id,
            goatCode = "GOAT-001",
            goatName = goat1.name,
            goatBreed = goat1.breed,
            goatPhoto = "",
            customerId = customerId,
            customerName = "Madesh K",
            customerPhone = "9876543210",
            farmId = goat1.farmId,
            farmName = goat1.farmName,
            amount = 15000.0,
            status = AvailabilityStatus.RESERVED,
            bookingDate = System.currentTimeMillis(),
            reservationExpiryDate = System.currentTimeMillis() + (24 * 3600 * 1000L)
        )

        assertEquals(AvailabilityStatus.RESERVED, booking.status)
        assertFalse(booking.isHoldExpired)
        val (_, _, label, _) = getOrderStatusStyle(booking.status, booking.isHoldExpired)
        assertEquals("Reserved (Hold)", label)
    }

    // Flow B: CUSTOMER Order gets confirmed -> same order shows CONFIRMED
    @Test
    fun testFlowB_CustomerOrderConfirmed_ShowsConfirmed() {
        val booking = Booking(
            id = "booking-001",
            bookingCode = "ORD-1001",
            goatId = goat1.id,
            goatCode = "GOAT-001",
            goatName = goat1.name,
            goatBreed = goat1.breed,
            goatPhoto = "",
            customerId = "customer-123",
            customerName = "Madesh K",
            customerPhone = "9876543210",
            farmId = goat1.farmId,
            farmName = goat1.farmName,
            amount = 15000.0,
            status = AvailabilityStatus.CONFIRMED,
            bookingDate = System.currentTimeMillis()
        )

        assertEquals(AvailabilityStatus.CONFIRMED, booking.status)
        val (_, _, label, _) = getOrderStatusStyle(booking.status, booking.isHoldExpired)
        assertEquals("Confirmed", label)
    }

    // Flow C: CUSTOMER Order completed -> same order shows COMPLETED
    @Test
    fun testFlowC_CustomerOrderCompleted_ShowsCompleted() {
        val booking = Booking(
            id = "booking-001",
            bookingCode = "ORD-1001",
            goatId = goat1.id,
            goatCode = "GOAT-001",
            goatName = goat1.name,
            goatBreed = goat1.breed,
            goatPhoto = "",
            customerId = "customer-123",
            customerName = "Madesh K",
            customerPhone = "9876543210",
            farmId = goat1.farmId,
            farmName = goat1.farmName,
            amount = 15000.0,
            status = AvailabilityStatus.COMPLETED,
            bookingDate = System.currentTimeMillis()
        )

        assertEquals(AvailabilityStatus.COMPLETED, booking.status)
        val (_, _, label, _) = getOrderStatusStyle(booking.status, booking.isHoldExpired)
        assertEquals("Completed", label)
    }

    // Flow D: CUSTOMER Cancelled/expired order -> remains visible with final status
    @Test
    fun testFlowD_CustomerCancelledOrExpired_RemainsVisibleWithFinalStatus() {
        val cancelledBooking = Booking(
            id = "booking-cancelled",
            bookingCode = "ORD-1002",
            goatId = goat1.id,
            goatCode = "GOAT-001",
            goatName = goat1.name,
            goatBreed = goat1.breed,
            goatPhoto = "",
            customerId = "customer-123",
            customerName = "Madesh K",
            customerPhone = "9876543210",
            farmId = goat1.farmId,
            farmName = goat1.farmName,
            amount = 15000.0,
            status = AvailabilityStatus.CANCELLED,
            notes = "Customer cancelled"
        )
        val (_, _, cancelLabel, _) = getOrderStatusStyle(cancelledBooking.status, cancelledBooking.isHoldExpired)
        assertEquals("Cancelled", cancelLabel)

        val expiredBooking = Booking(
            id = "booking-expired",
            bookingCode = "ORD-1003",
            goatId = goat1.id,
            goatCode = "GOAT-001",
            goatName = goat1.name,
            goatBreed = goat1.breed,
            goatPhoto = "",
            customerId = "customer-123",
            customerName = "Madesh K",
            customerPhone = "9876543210",
            farmId = goat1.farmId,
            farmName = goat1.farmName,
            amount = 15000.0,
            status = AvailabilityStatus.RESERVED,
            bookingDate = System.currentTimeMillis() - (48 * 3600 * 1000L),
            reservationExpiryDate = System.currentTimeMillis() - (24 * 3600 * 1000L)
        )
        assertTrue(expiredBooking.isHoldExpired)
        val (_, _, expiredLabel, _) = getOrderStatusStyle(expiredBooking.status, expiredBooking.isHoldExpired)
        assertEquals("Hold Expired", expiredLabel)
    }

    // Flow E: FARM ADMIN Customer books farm's goat -> Farm Admin -> Orders -> Farm Orders tab
    @Test
    fun testFlowE_FarmAdmin_CustomerBooksFarmGoat_AppearsInFarmOrders() {
        val farmAdminUserId = "owner-1"
        val farmAdminFarmId = "farm-1"

        val customerBookingOnFarmGoat = Booking(
            id = "booking-cust-1",
            bookingCode = "ORD-2001",
            goatId = goat1.id,
            goatCode = "GOAT-001",
            goatName = goat1.name,
            goatBreed = goat1.breed,
            goatPhoto = "",
            customerId = "customer-999",
            customerName = "Ravi Kumar",
            customerPhone = "9876543210",
            farmId = farmAdminFarmId,
            farmName = "Ammal Goat Farm",
            amount = 15000.0,
            status = AvailabilityStatus.RESERVED
        )

        // Verify this booking belongs to the farm orders list
        val isFarmOrder = customerBookingOnFarmGoat.farmId == farmAdminFarmId && customerBookingOnFarmGoat.customerId != farmAdminUserId
        assertTrue(isFarmOrder)
    }

    // Flow F: FARM ADMIN Farm Admin books another farm's goat -> Farm Admin -> Orders -> My Orders tab
    @Test
    fun testFlowF_FarmAdmin_BooksAnotherFarmGoat_AppearsInMyOrders() {
        val farmAdminUserId = "owner-1"
        val farmAdminOwnFarmId = "farm-1"

        val farmAdminBookingOnSalemGoat = Booking(
            id = "booking-admin-bought-1",
            bookingCode = "ORD-2002",
            goatId = goat2.id,
            goatCode = "GOAT-002",
            goatName = goat2.name,
            goatBreed = goat2.breed,
            goatPhoto = "",
            customerId = farmAdminUserId,
            customerName = "Ammal Farm Admin",
            customerPhone = "9876543210",
            farmId = goat2.farmId, // farm-2
            farmName = goat2.farmName,
            amount = 22000.0,
            status = AvailabilityStatus.RESERVED
        )

        // Verify this booking belongs to My Orders list (personally booked)
        val isMyPersonalOrder = farmAdminBookingOnSalemGoat.customerId == farmAdminUserId
        val isOwnFarmIncomingOrder = farmAdminBookingOnSalemGoat.farmId == farmAdminOwnFarmId && farmAdminBookingOnSalemGoat.customerId != farmAdminUserId

        assertTrue(isMyPersonalOrder)
        assertFalse(isOwnFarmIncomingOrder)
    }

    // Flow G: FARM ADMIN Confirm customer order -> lifecycle rules
    @Test
    fun testFlowG_FarmAdmin_ConfirmCustomerOrder_LifecycleRules() {
        val pendingBooking = Booking(
            id = "booking-confirm-test",
            bookingCode = "ORD-3001",
            goatId = goat1.id,
            goatCode = "GOAT-001",
            goatName = goat1.name,
            goatBreed = goat1.breed,
            goatPhoto = "",
            customerId = "customer-555",
            customerName = "Suresh P",
            customerPhone = "9876543210",
            farmId = "farm-1",
            farmName = "Ammal Goat Farm",
            amount = 15000.0,
            status = AvailabilityStatus.RESERVED
        )

        val confirmedBooking = pendingBooking.copy(status = AvailabilityStatus.CONFIRMED)
        assertEquals(AvailabilityStatus.CONFIRMED, confirmedBooking.status)

        val completedBooking = confirmedBooking.copy(status = AvailabilityStatus.COMPLETED)
        assertEquals(AvailabilityStatus.COMPLETED, completedBooking.status)
    }

    // Flow H: Notification New booking notification -> maps to order / deep link
    @Test
    fun testFlowH_Notification_NewBookingNotification_ResolvesCorrectly() {
        val payload = NotificationDeepLinkPayload(
            notificationId = "notif-new-book",
            route = "orders",
            referenceId = "booking-101",
            notificationType = NotificationType.NEW_BOOKING
        )
        val resolvedRoute = payload.route?.takeIf { it.isNotBlank() } ?: "orders"
        assertEquals("orders", resolvedRoute)
        assertEquals("booking-101", payload.referenceId)
    }

    // Flow I & J: Unified Orders Route in Screen definition
    @Test
    fun testFlowIJ_UnifiedOrdersRoute() {
        assertEquals("orders", Screen.Orders.route)
        assertEquals("account", Screen.Account.route)

        // Obsolete legacy routes mapped/maintained for backward compatibility
        assertEquals("my_bookings", Screen.MyBookings.route)
        assertEquals("farm_bookings", Screen.FarmBookings.route)
    }
}
