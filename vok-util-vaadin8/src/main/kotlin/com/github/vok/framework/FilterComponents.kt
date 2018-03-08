package com.github.vok.framework

import com.github.vok.karibudsl.*
import com.vaadin.data.Binder
import com.vaadin.shared.ui.datefield.DateTimeResolution
import com.vaadin.ui.*
import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*

/**
 * Used by filter components (such as [NumberInterval]) to create actual filter objects. The filters are expected to have properly
 * implemented [Any.equals], [Any.hashCode] and [Any.toString], to allow for filter expression simplification (e.g. to remove non-unique
 * filters from AND or OR expressions).
 *
 * The filter objects produced by this factory will be passed into the [com.vaadin.data.provider.DataProvider] in the [com.vaadin.data.provider.Query] object.
 *
 * @param F the type of the filter objects. Every database access type may have different filters: for example VoK-ORM
 */
interface FilterFactory<F> : Serializable {
    /**
     * ANDs given set of filters and returns a new filter.
     * @param filters set of filters, may be empty.
     * @return a filter which ANDs given filter set; returns `null` when the filter set is empty.
     */
    fun and(filters: Set<F>): F?
    /**
     * ORs given set of filters and returns a new filter.
     * @param filters set of filters, may be empty.
     * @return a filter which ORs given filter set; returns `null` when the filter set is empty.
     */
    fun or(filters: Set<F>): F?
    /**
     * Creates a filter which matches the value of given [propertyName] to given [value].
     */
    fun eq(propertyName: String, value: Any): F
    /**
     * Creates a filter which accepts only such values of given [propertyName] which are less than or equal to given [value].
     */
    fun le(propertyName: String, value: Any): F
    /**
     * Creates a filter which accepts only such values of given [propertyName] which are greater than or equal to given [value].
     */
    fun ge(propertyName: String, value: Any): F
    /**
     * Creates a filter which performs a case-insensitive substring matching of given [propertyName] to given [value].
     * @param value not empty; matching rows must contain this string. To emit SQL LIKE statement you need to prepend and append '%' to this string.
     */
    fun ilike(propertyName: String, value: String): F

    /**
     * Creates a filter which accepts only such values of given [propertyName] which are greater than or equal to given [min] and less than or equal to given [max].
     */
    fun between(propertyName: String, min: Any, max: Any): F = when {
        min == max -> eq(propertyName, min)
        else -> and(setOf(ge(propertyName, min), le(propertyName, max)))!!
    }
}

/**
 * A potentially open numeric range. If both [min] and [max] are `null`, then the interval accepts any number.
 * @property max the maximum accepted value, inclusive. If `null` then the numeric range has no upper limit.
 * @property min the minimum accepted value, inclusive. If `null` then the numeric range has no lower limit.
 */
data class NumberInterval<T : Number>(var max: T?, var min: T?) : Serializable {

    /**
     * Creates a filter out of this interval, using given [filterFactory].
     * @return a filter which matches the same set of numbers as this interval. Returns `null` for universal set interval.
     */
    fun <F> toFilter(propertyName: String, filterFactory: FilterFactory<F>): F? {
        if (isSingleItem) return filterFactory.eq(propertyName, max!!)
        if (max != null && min != null) {
            return filterFactory.between(propertyName, min!!, max!!)
        }
        if (max != null) return filterFactory.le(propertyName, max!!)
        if (min != null) return filterFactory.ge(propertyName, min!!)
        return null
    }

    /**
     * True if the interval consists of single number only.
     */
    val isSingleItem: Boolean
        get() = max != null && min != null && max == min

    /**
     * True if the interval includes all possible numbers (both [min] and [max] are `null`).
     */
    val isUniversalSet: Boolean
        get() = max == null && min == null
}

/**
 * Only shows a single button as its contents. When the button is clicked, it opens a dialog and allows the user to specify a range
 * of numbers. When the user sets the values, the dialog is
 * hidden and the number range is set as the value of the popup.
 *
 * The current numeric range is also displayed as the caption of the button.
 */
class NumberFilterPopup : CustomField<NumberInterval<Double>?>() {

    private lateinit var ltInput: TextField
    private lateinit var gtInput: TextField
    @Suppress("UNCHECKED_CAST")
    private val binder: Binder<NumberInterval<Double>> = Binder(NumberInterval::class.java as Class<NumberInterval<Double>>).apply { bean = NumberInterval(null, null) }
    private var internalValue: NumberInterval<Double>? = null

    override fun initContent(): Component? {
        return PopupView(SimpleContent.EMPTY).apply {
            w = fillParent; minimizedValueAsHTML = "All"; isHideOnMouseOut = false
            verticalLayout {
                w = wrapContent
                horizontalLayout {
                    gtInput = textField {
                        placeholder = "at least"
                        w = 100.px
                        bind(binder).toDouble().bind(NumberInterval<Double>::min)
                    }
                    label("..") {
                        w = wrapContent
                    }
                    ltInput = textField {
                        placeholder = "at most"
                        w = 100.px
                        bind(binder).toDouble().bind(NumberInterval<Double>::max)
                    }
                }
                horizontalLayout {
                    alignment = Alignment.MIDDLE_RIGHT
                    button("Clear") {
                        onLeftClick {
                            binder.fields.forEach { it.clear() }
                            value = null
                            isPopupVisible = false
                        }
                    }
                    button("Ok") {
                        onLeftClick {
                            value = binder.bean.copy()
                            isPopupVisible = false
                        }
                    }
                }
            }
        }
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        ltInput.isEnabled = !readOnly
        gtInput.isEnabled = !readOnly
    }

    private fun updateCaption() {
        val content = content as PopupView
        val value = value
        if (value == null || value.isUniversalSet) {
            content.minimizedValueAsHTML = "All"
        } else {
            if (value.isSingleItem) {
                content.minimizedValueAsHTML = "[x] = ${value.max}"
            } else if (value.min != null && value.max != null) {
                content.minimizedValueAsHTML = "${value.min} < [x] < ${value.max}"
            } else if (value.min != null) {
                content.minimizedValueAsHTML = "[x] >= ${value.min}"
            } else if (value.max != null) {
                content.minimizedValueAsHTML = "[x] <= ${value.max}"
            }
        }
    }

    override fun doSetValue(value: NumberInterval<Double>?) {
        internalValue = value?.copy()
        binder.bean = value?.copy() ?: NumberInterval<Double>(null, null)
        updateCaption()
    }

    override fun getValue() = internalValue?.copy()
}

/**
 * Converts this class to its non-primitive counterpart. For example, converts `int.class` to `Integer.class`.
 * @return converts class of primitive type to appropriate non-primitive class; other classes are simply returned as-is.
 */
@Suppress("UNCHECKED_CAST")
val <T> Class<T>.nonPrimitive: Class<T> get() = when(this) {
    Integer.TYPE -> Integer::class.java as Class<T>
    java.lang.Long.TYPE -> Long::class.java as Class<T>
    java.lang.Float.TYPE -> Float::class.java as Class<T>
    java.lang.Double.TYPE -> java.lang.Double::class.java as Class<T>
    java.lang.Short.TYPE -> Short::class.java as Class<T>
    java.lang.Byte.TYPE -> Byte::class.java as Class<T>
    else -> this
}

/**
 * A potentially open date range. If both [from] and [to] are `null`, then the interval accepts any date.
 * @property to the maximum accepted value, inclusive. If `null` then the date range has no upper limit.
 * @property from the minimum accepted value, inclusive. If `null` then the date range has no lower limit.
 */
data class DateInterval(var from: LocalDateTime?, var to: LocalDateTime?) : Serializable {
    /**
     * True if the interval includes all possible numbers (both [from] and [to] are `null`).
     */
    val isUniversalSet: Boolean
        get() = from == null && to == null

    private fun <T: Comparable<T>, F> T.legeFilter(propertyName: String, filterFactory: FilterFactory<F>, isLe: Boolean): F =
            if (isLe) filterFactory.le(propertyName, this) else filterFactory.ge(propertyName, this)

    private fun <F> LocalDateTime.toFilter(propertyName: String, filterFactory: FilterFactory<F>, fieldType: Class<*>, isLe: Boolean): F {
        return when (fieldType) {
            LocalDateTime::class.java -> legeFilter(propertyName, filterFactory, isLe)
            LocalDate::class.java -> toLocalDate().legeFilter(propertyName, filterFactory, isLe)
            else -> {
                atZone(browserTimeZone).toInstant().toDate.legeFilter(propertyName, filterFactory, isLe)
            }
        }
    }

    fun <F: Any> toFilter(propertyName: String, filterFactory: FilterFactory<F>, fieldType: Class<*>): F? {
        val filters = listOf(from?.toFilter(propertyName, filterFactory, fieldType, false), to?.toFilter(propertyName, filterFactory, fieldType, true)).filterNotNull()
        return filterFactory.and(filters.toSet())
    }
}

/**
 * Only shows a single button as its contents. When the button is clicked, it opens a dialog and allows the user to specify a range
 * of dates. When the user sets the values, the dialog is
 * hidden and the date range is set as the value of the popup.
 *
 * The current date range is also displayed as the caption of the button.
 */
class DateFilterPopup: CustomField<DateInterval?>() {
    private val formatter get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(UI.getCurrent().locale ?: Locale.getDefault())
    private lateinit var fromField: InlineDateTimeField
    private lateinit var toField: InlineDateTimeField
    private lateinit var set: Button
    private lateinit var clear: Button
    /**
     * The desired resolution of this filter popup, defaults to [DateTimeResolution.MINUTE].
     */
    var resolution: DateTimeResolution
        get() = fromField.resolution
        set(value) {
            fromField.resolution = value
            toField.resolution = value
        }

    private var internalValue: DateInterval? = null

    init {
        styleName = "datefilterpopup"
        // force initcontents so that fromField and toField are initialized and one can set resolution to them
        content
    }

    override fun doSetValue(value: DateInterval?) {
        internalValue = value?.copy()
        fromField.value = internalValue?.from
        toField.value = internalValue?.to
        updateCaption()
    }

    override fun getValue() = internalValue?.copy()

    private fun format(date: LocalDateTime?) = if (date == null) "" else formatter.format(date)

    private fun updateCaption() {
        val content = content as PopupView
        val value = value
        if (value == null || value.isUniversalSet) {
            content.minimizedValueAsHTML = "All"
        } else {
            content.minimizedValueAsHTML = "${format(fromField.value)} - ${format(toField.value)}"
        }
    }

    private fun truncateDate(date: LocalDateTime?, resolution: DateTimeResolution, start: Boolean): LocalDateTime? {
        @Suppress("NAME_SHADOWING")
        var date = date ?: return null
        for (res in DateTimeResolution.values().slice(0..resolution.ordinal - 1)) {
            if (res == DateTimeResolution.SECOND) {
                date = date.withSecond(if (start) 0 else 59)
            } else if (res == DateTimeResolution.MINUTE) {
                date = date.withMinute(if (start) 0 else 59)
            } else if (res == DateTimeResolution.HOUR) {
                date = date.withHour(if (start) 0 else 23)
            } else if (res == DateTimeResolution.DAY) {
                date = date.withDayOfMonth(if (start) 1 else date.toLocalDate().lengthOfMonth())
            } else if (res == DateTimeResolution.MONTH) {
                date = date.withMonth(if (start) 1 else 12)
            }
        }
        return date
    }

    override fun initContent(): Component? {
        return PopupView(SimpleContent.EMPTY).apply {
            w = fillParent; minimizedValueAsHTML = "All"; isHideOnMouseOut = false
            verticalLayout {
                styleName = "datefilterpopupcontent"; setSizeUndefined(); isSpacing = true; isMargin = true
                horizontalLayout {
                    isSpacing = true
                    fromField = inlineDateTimeField()
                    toField = inlineDateTimeField()
                }
                horizontalLayout {
                    alignment = Alignment.BOTTOM_RIGHT
                    isSpacing = true
                    set = button("Set", {
                        value = DateInterval(truncateDate(fromField.value, resolution, true), truncateDate(toField.value, resolution, false))
                        isPopupVisible = false
                    })
                    clear = button("Clear", {
                        value = null
                        isPopupVisible = false
                    })
                }
            }
        }
    }

    override fun attach() {
        super.attach()
        fromField.locale = locale
        toField.locale = locale
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        set.isEnabled = !readOnly
        clear.isEnabled = !readOnly
        fromField.isEnabled = !readOnly
        toField.isEnabled = !readOnly
    }
}
