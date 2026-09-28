package com.ammalfarm.adusanthai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class PolicySection(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val summary: String,
    val details: List<String>,
    val highlights: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalPolicyScreen(
    initialTab: Int = 0,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val privacySections = remember {
        listOf(
            PolicySection(
                id = "priv_overview",
                title = "1. Introduction & Overview",
                icon = Icons.Outlined.Shield,
                summary = "We respect your digital privacy and are committed to protecting the personal data of livestock buyers, breeders, and farm partners.",
                details = listOf(
                    "This Privacy Policy outlines how the Livestock Marketplace Platform collects, processes, securely stores, and protects personal information in accordance with applicable data protection regulations and electronic commerce guidelines.",
                    "By creating an account, browsing livestock listings, booking reservations, or registering as a Farm Partner, you consent to the data practices described in this policy.",
                    "This policy was last updated in September 2026 and applies to all services offered within this application."
                ),
                highlights = listOf("Effective Date: Sept 2026", "Applicable across all platform features")
            ),
            PolicySection(
                id = "priv_collection",
                title = "2. Information We Collect",
                icon = Icons.Outlined.ContactPage,
                summary = "We collect necessary identity, farm accreditation, and booking communication details.",
                details = listOf(
                    "Customer Account Data: Name, verified email address, phone number, and primary district or delivery location.",
                    "Farm Partner Data: Farm business name, government farm registration number, physical address, GPS coordinates, breeder certification details, and veterinarian health certificates.",
                    "Livestock Listing Media: High-resolution photographs, breed pedigrees, weight/age records, vaccination records, and pricing information provided by breeders.",
                    "Reservation & Booking Data: Booking timestamps, 24-hour reservation statuses, and direct seller notes.",
                    "Technical Diagnostics: Device hardware model, operating system version, and application crash diagnostics used solely to improve stability."
                ),
                highlights = listOf("No financial card data stored", "Farm records verified prior to listing approval")
            ),
            PolicySection(
                id = "priv_security",
                title = "3. Cloud Security & Data Protection Standards",
                icon = Icons.Outlined.CloudDone,
                summary = "Our cloud infrastructure utilizes industry-standard encryption, strict access controls, and secure data storage.",
                details = listOf(
                    "Encrypted Transmission: All communications between your mobile device and cloud servers are encrypted using modern standard encryption protocols (TLS/HTTPS).",
                    "Access Control: Strict role-based access control policies ensure only authenticated users can access their personal reservations, and only authorized farm partners can manage inventory.",
                    "Cryptographic Security: Passwords and sensitive authentication credentials are salted and cryptographically hashed before transmission and storage in secure credential vaults.",
                    "Local Device Storage: To support responsive browsing and offline reliability, catalog data is stored locally in private, encrypted application storage on your device."
                ),
                highlights = listOf("Encrypted Data", "Role-Based Access", "Secure Local Storage")
            ),
            PolicySection(
                id = "priv_usage",
                title = "4. How We Use Your Information",
                icon = Icons.Outlined.ManageAccounts,
                summary = "Data is used strictly to facilitate livestock transactions, ensure breed integrity, and prevent fraud.",
                details = listOf(
                    "Platform Facilitation: Facilitating authentic 24-hour livestock reservations between buyers and licensed farm breeders.",
                    "Quality & Pedigree Verification: Reviewing breeder credentials, breed purity claims, and health documentation to safeguard livestock buyers.",
                    "In-App Notifications: Dispatching updates regarding reservation confirmations, farm approvals, report resolutions, and important safety announcements.",
                    "Fraud Prevention: Detecting duplicate listings, spam communications, or unauthorized trading."
                ),
                highlights = listOf("Direct farm-to-buyer connection", "Automated booking notifications")
            ),
            PolicySection(
                id = "priv_sharing",
                title = "5. Data Sharing & Third Parties",
                icon = Icons.Outlined.Share,
                summary = "We never sell or rent your personal data to external advertisers or brokers.",
                details = listOf(
                    "Transaction Counterparts: When a customer places a 24-hour reservation, their contact name and verified phone number are shared exclusively with the respective farm breeder to arrange animal inspection and pickup.",
                    "Regulatory & Legal Compliance: We may disclose information if mandated by law, judicial subpoena, or animal health regulatory agencies investigating livestock welfare violations.",
                    "Service Infrastructure: Data is hosted in secure, certified cloud server facilities adhering to standard industry data compliance protocols."
                ),
                highlights = listOf("Zero third-party advertising trackers", "Direct contact shared only on active bookings")
            ),
            PolicySection(
                id = "priv_rights",
                title = "6. Your Rights & Data Controls",
                icon = Icons.Outlined.LockPerson,
                summary = "You have full control over your personal profile, listings, and stored data.",
                details = listOf(
                    "Access & Rectification: You can review and modify your display name, phone number, and theme preferences at any time in the Profile screen.",
                    "Account Deletion: You have the right to request full account deactivation and deletion of personal records by contacting our Data Protection Officer.",
                    "Notification Preferences: You can clear in-app notifications and adjust push preferences at any time within the Notification Center.",
                    "Listing Removal: Farm partners can withdraw or mark livestock listings as sold whenever transactions conclude."
                ),
                highlights = listOf("Right to rectify data", "Right to request account deletion")
            ),
            PolicySection(
                id = "priv_contact",
                title = "7. Grievance Redressal & Contact",
                icon = Icons.Outlined.SupportAgent,
                summary = "Reach our dedicated Data Protection & Compliance desk with any concerns.",
                details = listOf(
                    "Email: privacy@livestockmarketplace.com",
                    "Grievance Officer: Chief Compliance Officer",
                    "Department: Data Protection & Legal Compliance Division",
                    "Response Time: We strive to acknowledge all formal privacy requests within 48 business hours."
                ),
                highlights = listOf("48-Hour Response SLA", "Dedicated Grievance Officer")
            )
        )
    }

    val termsSections = remember {
        listOf(
            PolicySection(
                id = "term_acceptance",
                title = "1. Acceptance of Terms",
                icon = Icons.Outlined.Gavel,
                summary = "Using this marketplace constitutes full agreement with these binding conditions.",
                details = listOf(
                    "These Terms and Conditions govern the access and use of the Livestock Marketplace Application, connecting buyers, livestock farmers, and certified breeders.",
                    "If you do not agree with any provision of these Terms, you must immediately cease accessing the platform and delete the application from your device.",
                    "Users must be at least 18 years of age or represent a legally recognized agricultural business or farm enterprise."
                ),
                highlights = listOf("Legally binding agreement", "Minimum age: 18 years")
            ),
            PolicySection(
                id = "term_role",
                title = "2. Marketplace Role & Intermediary Scope",
                icon = Icons.Outlined.Storefront,
                summary = "The app is an informational and reservation coordination marketplace, not an animal owner or logistics agent.",
                details = listOf(
                    "Platform Scope: The application acts as a digital marketplace allowing registered farm partners to display verified livestock listings and allowing interested customers to hold 24-hour reservations.",
                    "Direct Transactions: Physical livestock examination, veterinary check, and financial transactions take place directly between the buyer and the farm breeder at the farm location.",
                    "Independent Breeders: Registered farms are independent agricultural entities and do not constitute employees, subsidiaries, or legal agents of the application."
                ),
                highlights = listOf("Direct farm-gate transactions", "Breeder independence")
            ),
            PolicySection(
                id = "term_breeder",
                title = "3. Farm Partner & Breeder Obligations",
                icon = Icons.Outlined.Agriculture,
                summary = "Breeder partners must maintain strict standards of breed purity, accurate descriptions, and health transparency.",
                details = listOf(
                    "Accurate Representation: All photographs, weights, age estimations, and pedigree claims must be accurate and unmanipulated.",
                    "Mandatory Health Disclosures: Breeders must disclose any known pre-existing medical conditions, vaccination history, deworming dates, and breeding history prior to finalizing reservations.",
                    "Administrative Verification: New farm profiles remain in PENDING status until credentials are authenticated by Super Administrators. Unapproved farms cannot publish public livestock listings."
                ),
                highlights = listOf("Strict breed pedigree accuracy", "Super Admin farm verification")
            ),
            PolicySection(
                id = "term_reservation",
                title = "4. 24-Hour Reservation System",
                icon = Icons.Outlined.Timer,
                summary = "Reservations protect buyers and breeders by holding livestock for inspection for up to 24 hours.",
                details = listOf(
                    "Reservation Window: When a customer reserves a goat, the listing status transitions to RESERVED for a maximum duration of 24 hours.",
                    "Farm Visit & Inspection: The buyer must coordinate with the breeder to visit the farm or arrange certified livestock logistics before the 24-hour period elapses.",
                    "Breeder Confirmation: The verified farm partner reserves the right to confirm, fulfill, or reject reservations based on livestock availability and genuine intent.",
                    "Automated Expiry: If no transaction takes place within 24 hours and the breeder does not mark the booking complete, the livestock listing automatically reverts to AVAILABLE."
                ),
                highlights = listOf("24-Hour exclusive hold", "Automated expiration on no-show")
            ),
            PolicySection(
                id = "term_welfare",
                title = "5. Animal Welfare & Humane Handling",
                icon = Icons.Outlined.Pets,
                summary = "Strict zero-tolerance policy against animal cruelty, neglect, or illegal livestock trafficking.",
                details = listOf(
                    "Legal Compliance: All livestock transactions and farm operations must strictly adhere to the Prevention of Cruelty to Animals Act, 1960 and transport of animals guidelines.",
                    "Humane Transportation: Buyers and breeders must guarantee humane, adequately ventilated, and stress-free transport arrangements for purchased livestock.",
                    "Immediate Sanctions: Any partner reported or verified to engage in animal neglect, inhumane confinement, or illegal trafficking will face permanent ban and referral to veterinary authorities."
                ),
                highlights = listOf("Zero tolerance for animal cruelty", "Transport compliance mandatory")
            ),
            PolicySection(
                id = "term_conduct",
                title = "6. Community Conduct & Communication",
                icon = Icons.Outlined.Groups,
                summary = "Members must maintain ethical communication and constructive livestock trade practices.",
                details = listOf(
                    "Professional Engagement: Customers and breeders must interact respectfully regarding livestock inquiries, appointments, and farm visits.",
                    "Prohibited Content: Defamatory remarks, profane language, unverified competitor sabotage, and spam inquiries are strictly prohibited.",
                    "Moderation: Super Administrators monitor all platform reports and reserve the right to penalize malicious users or disable abusive accounts."
                ),
                highlights = listOf("Respectful trade communication", "Strict content moderation")
            ),
            PolicySection(
                id = "term_liability",
                title = "7. Limitation of Liability & Dispute Settlement",
                icon = Icons.Outlined.Balance,
                summary = "Clear limitation of platform liability with arbitration under applicable statutory laws.",
                details = listOf(
                    "Warranty Disclaimer: The application does not provide biological or genetic warranties regarding livestock longevity, reproductive fecundity, or future weight gains.",
                    "Physical Inspection: Buyers must inspect animals thoroughly in person and obtain a certified veterinary check before handing over payment.",
                    "Governing Law: These Terms are governed by and construed in accordance with applicable laws, with jurisdiction in designated competent civil courts."
                ),
                highlights = listOf("Physical inspection required", "Dispute arbitration framework")
            ),
            PolicySection(
                id = "term_updates",
                title = "8. Amendments & Contact",
                icon = Icons.Outlined.Update,
                summary = "Terms may be updated periodically to reflect new regulatory standards.",
                details = listOf(
                    "We reserve the right to modify these Terms at any time. Continued use of the application after amendments constitute acceptance.",
                    "For inquiries or legal notices, contact our Legal Team at legal@livestockmarketplace.com."
                ),
                highlights = listOf("Regular regulatory updates", "legal@livestockmarketplace.com")
            )
        )
    }

    val currentSections = if (selectedTab == 0) privacySections else termsSections

    val filteredSections = remember(currentSections, searchQuery) {
        if (searchQuery.isBlank()) currentSections
        else {
            currentSections.filter { section ->
                section.title.contains(searchQuery, ignoreCase = true) ||
                section.summary.contains(searchQuery, ignoreCase = true) ||
                section.details.any { it.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("legal_policy_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (selectedTab == 0) "Privacy Policy" else "Terms & Conditions",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Legal Documentation • Last Updated Sept 2026",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("legal_policy_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab Selector
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        searchQuery = ""
                        scope.launch { listState.scrollToItem(0) }
                    },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Privacy Policy", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium)
                        }
                    },
                    modifier = Modifier.testTag("tab_privacy_policy")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        searchQuery = ""
                        scope.launch { listState.scrollToItem(0) }
                    },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Gavel,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Terms & Conditions", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium)
                        }
                    },
                    modifier = Modifier.testTag("tab_terms_conditions")
                )
            }

            // Search Bar for Quick Legal Clause Lookups
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = if (selectedTab == 0) "Search privacy clauses (e.g., data security, account deletion)..." else "Search terms (e.g., reservation, liability)...",
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search clauses",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("legal_search_field")
                )
            }

            // Highlights Banner / Badge Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedTab == 0) {
                    item {
                        HighlightChip(
                            icon = Icons.Outlined.Lock,
                            text = "End-to-End Encrypted",
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    item {
                        HighlightChip(
                            icon = Icons.Outlined.Security,
                            text = "Role-Based Access",
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    item {
                        HighlightChip(
                            icon = Icons.Outlined.Block,
                            text = "Zero Data Selling",
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                } else {
                    item {
                        HighlightChip(
                            icon = Icons.Outlined.Verified,
                            text = "Verified Farm Breeders",
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    item {
                        HighlightChip(
                            icon = Icons.Outlined.Timer,
                            text = "24-Hour Fair Reservation",
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    item {
                        HighlightChip(
                            icon = Icons.Outlined.Pets,
                            text = "Cruelty-Free Mandate",
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // Main Content List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)
            ) {
                // Header overview card
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (selectedTab == 0) Icons.Default.Security else Icons.Default.Gavel,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (selectedTab == 0) "Your Privacy Matters" else "Standard Terms of Service",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = if (selectedTab == 0)
                                        "Learn how our marketplace protects your identity, farm registration, and livestock booking records."
                                    else
                                        "Understand the code of conduct, 24-hour reservation terms, and animal welfare commitments required of all members.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (filteredSections.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "No clauses match \"$searchQuery\"",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Try searching with broader terms like 'data', 'farm', 'reservation', or 'security'.",
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = { searchQuery = "" },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Reset Search")
                                }
                            }
                        }
                    }
                } else {
                    items(filteredSections, key = { it.id }) { section ->
                        PolicySectionCard(section = section)
                    }
                }

                // Bottom Acknowledgement & Contact
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Have questions about our policies?",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Email: compliance@goatmarketplace.in • Tel: +91 (0422) 298-4400",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Tamil Nadu Livestock Pedigree & Breeding Network © 2026",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PolicySectionCard(
    section: PolicySection,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("policy_section_${section.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = section.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = section.summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse clause" else "Expand clause",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    section.details.forEach { paragraph ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "•",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp
                            )
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (section.highlights.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            section.highlights.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HighlightChip(
    icon: ImageVector,
    text: String,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}
