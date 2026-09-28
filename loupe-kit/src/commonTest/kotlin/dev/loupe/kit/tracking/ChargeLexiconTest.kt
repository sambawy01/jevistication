package dev.loupe.kit.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargeLexiconTest {
    private fun charge(t: String) = ChargeLexicon.CHARGE.containsMatchIn(TrackingText.matchForm(t))
    private fun notCharge(t: String) = ChargeLexicon.NOT_A_CHARGE.containsMatchIn(TrackingText.matchForm(t))
    private fun subscription(t: String) = ChargeLexicon.SUBSCRIPTION.containsMatchIn(TrackingText.matchForm(t))

    @Test
    fun chargeWordsInEnglishArabicEgyptianAndFranco() {
        for (t in listOf("We've charged your card", "Thank you for your payment", "Total amount paid", "This is a receipt for your payment",
                         "تم الدفع بنجاح", "تم خصم مبلغ ٩٩ جنيه", "تم سداد فاتورة", "تم تجديد اشتراكك", "تم تحويل ١٬٥٠٠ جنيه",
                         "اتخصم من الفيزا", "el visa et5asam menha", "dafa3t el fatoora")) {
            assertTrue(charge(t), t)
        }
        for (t in listOf("Subscribe now for just EGP 99", "Your bill is ready", "Please pay by 30/09", "اشترك الآن", "رقم مرجعي للدفع")) {
            assertFalse(charge(t), t)
        }
    }

    @Test
    fun notAChargeWords() {
        for (t in listOf("Your account was credited with EGP 15,000", "We have refunded $4.99", "Amount due: EGP 842",
                         "Subscribe now for just $9.99", "Flash sale: 30% off", "تم إيداع مبلغ", "طلب تحويل", "اشترك الآن في باقة",
                         "ادفع قبل الموعد", "momken te7wel 200 geneh", "eshtrek delwa2ty")) {
            assertTrue(notCharge(t), t)
        }
        assertFalse(notCharge("Amount paid: EGP 165.00"))
    }

    @Test
    fun futureAndFailedPaymentsAreNotCharges() {
        for (t in listOf("You will be charged EGP 165 on 3 July", "Your plan will be renewed on 3 July", "Your membership will renew on 3 July",
                         "You will be billed EGP 99 next month", "Payment reminder", "Your upcoming payment", "Your payment of EGP 165 was declined",
                         "Your payment failed", "Transaction unsuccessful", "الفاتورة غير مدفوعة", "الحالة: غير مدفوع", "فشل الدفع",
                         "فشلت عملية الدفع", "العملية مرفوضة", "عملية مرفوض", "لم تتم عملية الدفع")) {
            assertTrue(notCharge(t), t)
        }
        for (t in listOf("Your membership was renewed", "Amount paid: EGP 165.00", "المبلغ المدفوع: ٦٩٫٩٩ ج.م", "تم الدفع بنجاح")) {
            assertFalse(notCharge(t), t)
        }
    }

    @Test
    fun subscriptionWords() {
        for (t in listOf("Monthly subscription", "Your membership", "اشتراك شهري", "باقة فليكس", "eshterak shahry", "fatoora el net")) {
            assertTrue(subscription(t), t)
        }
    }

    @Test
    fun knownMerchantsAndTheirSpellings() {
        assertEquals("Netflix", MerchantHints.merchant("Your Netflix payment receipt"))
        assertEquals("Netflix", MerchantHints.canonical("NETFLIX.COM"))
        assertEquals("Shahid", MerchantHints.canonical("SHAHID VIP"))
        assertEquals("Vodafone", MerchantHints.merchant("فاتورة فودافون"))
        assertEquals("Anghami", MerchantHints.merchant("le Anghami Plus"))
        assertEquals("Jumia Egypt", MerchantHints.canonical("Jumia Egypt"))
        assertNull(MerchantHints.merchant("Orange juice 25 EGP"), "orange the fruit is not Orange Egypt")
    }

    @Test
    fun weOnlyInCapitals() {
        assertEquals("WE", MerchantHints.merchant("WE\nتم دفع فاتورة الإنترنت الأرضي بنجاح"))
        assertNull(MerchantHints.merchant("we paid for dinner"))
        assertEquals("WE", MerchantHints.merchant("المصرية للاتصالات"))
    }

    @Test
    fun railsNameThePayeeOrTheService() {
        assertEquals("نادي الجزيرة", MerchantHints.merchant("InstaPay\nتم تحويل ١٬٥٠٠ جنيه\nالمستفيد: نادي الجزيرة"))
        assertEquals("Orange", MerchantHints.merchant("فوري\nإيصال سداد\nالخدمة: أورنج - فاتورة موبايل"))
        assertEquals("InstaPay", MerchantHints.merchant("InstaPay\nتم تحويل ٢٠٠ جنيه"))
    }

    @Test
    fun weInACapitalisedSentenceIsNotTheCompany() {
        for (t in listOf("WE'VE RECEIVED YOUR PAYMENT", "THANK YOU. WE HAVE CHARGED YOUR CARD", "WE ARE HAPPY TO CONFIRM", "WE’VE GOT IT")) {
            assertNull(MerchantHints.merchant(t), t)
        }
        for (t in listOf("WE\nتم دفع الفاتورة", "WE - فاتورة الإنترنت", "Your WE Internet bill", "WE Home Internet", "MY WE app")) {
            assertEquals("WE", MerchantHints.merchant(t), t)
        }
    }

    @Test
    fun fawryAsTheWordInstantIsNotTheRail() {
        assertNull(MerchantHints.merchant("تم تحويل ٥٠٠ جنيه تحويل فوري"), "فوري as instant")
        assertNull(MerchantHints.merchant("الرد فوري والخدمة ممتازة"))
        assertEquals("Fawry", MerchantHints.merchant("فوري\nتم الدفع ١٥٠ جنيه"), "the rail as a heading")
        assertEquals("Fawry", MerchantHints.merchant("تم الدفع ١٥٠ جنيه\nكود فوري: 9912-4455"))
        assertEquals("Fawry", MerchantHints.merchant("Fawry receipt\nPaid EGP 150"))
    }

    @Test
    fun anEmailsMerchantIsItsSenderBeforeHowItWasPaid() {
        assertEquals("Talabat", MerchantHints.ofEmail("Your Talabat order", "Paid via InstaPay\nTotal: EGP 240.00", "Talabat", "no-reply@talabat.com"))
        assertEquals("Talabat", MerchantHints.ofEmail("Your order", "Paid with Vodafone Cash\nTotal: EGP 240.00", "Talabat", "no-reply@talabat.com"))
        assertEquals("Jumia Egypt", MerchantHints.ofEmail("Order paid", "تم الدفع عن طريق فوري\nالإجمالي: ٥٠٠ جنيه", "Jumia Egypt", "no-reply@jumia.com.eg"))
        assertEquals("Talabat", MerchantHints.ofEmail("Order", "Paid using Orange Money\nTotal: EGP 90.00", "Talabat", "x@talabat.com"))
        assertEquals("Netflix", MerchantHints.ofEmail("Your receipt", "Amount paid: EGP 165.00", "Netflix", "info@mailer.netflix.com"))
        assertEquals("Spotify", MerchantHints.ofEmail("إيصال اشتراك Spotify Premium", "المبلغ المدفوع: ٦٩٫٩٩ ج.م", null, "no-reply@spotify.com"))
        assertEquals("iCloud", MerchantHints.ofEmail("Your receipt from Apple", "iCloud+ with 50 GB\nTotal: \$0.99", "Apple", "no_reply@email.apple.com"),
                     "a brand in the body of a store's receipt")
        assertEquals("نادي الجزيرة", MerchantHints.ofEmail("InstaPay", "تم تحويل ١٬٥٠٠ جنيه\nالمستفيد: نادي الجزيرة", "InstaPay", "noreply@instapay.eg"),
                     "a rail's payee line")
        assertEquals("talabat.com", MerchantHints.ofEmail("Order", "Paid via InstaPay", null, "no-reply@talabat.com"))
        assertEquals("InstaPay", MerchantHints.ofEmail("Transfer", "Paid via InstaPay", null, null))
    }

    @Test
    fun theFirstLineNamesAShopUnlessItIsGeneric() {
        assertEquals("Cafe Luna", MerchantHints.fromFirstLine("Cafe Luna\n2 flat whites £7.00\nPaid"))
        assertNull(MerchantHints.fromFirstLine("Receipt\nTotal £7.00"))
        assertNull(MerchantHints.fromFirstLine("إيصال\nالمبلغ ٥٠ جنيه"))
        assertNull(MerchantHints.fromFirstLine("0100 123 4567 88\nPaid"))
    }
}
