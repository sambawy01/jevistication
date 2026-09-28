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
    fun theFirstLineNamesAShopUnlessItIsGeneric() {
        assertEquals("Cafe Luna", MerchantHints.fromFirstLine("Cafe Luna\n2 flat whites £7.00\nPaid"))
        assertNull(MerchantHints.fromFirstLine("Receipt\nTotal £7.00"))
        assertNull(MerchantHints.fromFirstLine("إيصال\nالمبلغ ٥٠ جنيه"))
        assertNull(MerchantHints.fromFirstLine("0100 123 4567 88\nPaid"))
    }
}
