package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.util.PolicyConsentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Adu Santhai", appName)
  }

  @Test
  fun `policy consent manager defaults to false and persists true once accepted`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = PolicyConsentManager(context)
    
    // First time should be false
    assertFalse(manager.hasAcceptedPolicies())

    // Accept once
    manager.setPoliciesAccepted(true)
    assertTrue(manager.hasAcceptedPolicies())
    assertNotNull(manager.getAcceptedDateFormatted())

    // A new instance should remember the saved state (no need to ask every time)
    val newInstance = PolicyConsentManager(context)
    assertTrue(newInstance.hasAcceptedPolicies())
  }

  @Test
  fun `policy consent state can be reset and toggled correctly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = PolicyConsentManager(context)
    manager.setPoliciesAccepted(false)
    assertFalse(manager.hasAcceptedPolicies())

    manager.setPoliciesAccepted(true)
    assertTrue(manager.hasAcceptedPolicies())
  }
}
