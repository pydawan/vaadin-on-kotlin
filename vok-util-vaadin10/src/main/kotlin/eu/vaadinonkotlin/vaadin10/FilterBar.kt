package eu.vaadinonkotlin.vaadin10

import com.github.mvysny.karibudsl.v10.DateInterval
import com.github.mvysny.karibudsl.v10.getColumnBy
import com.github.mvysny.vokdataloader.Filter
import com.github.mvysny.vokdataloader.FullTextFilter
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasSize
import com.vaadin.flow.component.HasValue
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.HeaderRow
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.ConfigurableFilterDataProvider
import com.vaadin.flow.data.value.HasValueChangeMode
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.shared.Registration
import eu.vaadinonkotlin.FilterFactory
import java.io.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

fun <T : Any, F : Any> HeaderRow.asFilterBar(grid: Grid<T>, filterFactory: FilterFactory<F>): FilterBar<T, F> =
        FilterBar(this, grid, filterFactory)

typealias VokFilterBar<T> = FilterBar<T, Filter<T>>

/**
 * Creates a [FilterBar] which produces VOK [Filter]s. Perfect for using with the
 * [VokDataProvider].
 * @param T the bean type present in the [grid]
 * @param grid the owner grid
 */
fun <T : Any> HeaderRow.asFilterBar(grid: Grid<T>): VokFilterBar<T> =
        FilterBar<T, Filter<T>>(this, grid, DataLoaderFilterFactory())

/**
 * Wraps [HeaderRow] and helps you build filter components.
 *
 * After the user
 * changes the value in the filter UI component, a new Grid filter of type [FILTER] is computed and
 * set to [Grid.getDataProvider].
 *
 * Every filter component is configured using the [componentConfigurator] closure.
 * @param BEAN the type of items in the grid.
 * @param FILTER the type of the filters accepted by grid's [ConfigurableFilterDataProvider].
 * @param grid the owner grid. It is expected that [Grid.getDataProvider] is of type [VokDataProvider]<BEAN>
 * (or [ConfigurableFilterDataProvider]<BEAN, FILTER, FILTER>).
 * @property headerRow the wrapped header row
 * @param filterFactory used to combine filter values when multiple filters are applied
 * (using the [FilterFactory.and] function).
 */
open class FilterBar<BEAN : Any, FILTER : Any>(
        val headerRow: HeaderRow,
        val grid: Grid<BEAN>,
        val filterFactory: FilterFactory<FILTER>
) : Serializable {

    private var currentFilter: FILTER? = null

    /**
     * Lists all currently registered bindings. The value is a registration of the
     * [Binding.addFilterChangeListener].
     */
    private val bindings: MutableMap<Binding<BEAN, FILTER>, Registration> = mutableMapOf()

    private fun applyFilterToGrid(filter: FILTER?) {
        @Suppress("UNCHECKED_CAST")
        (grid.dataProvider as ConfigurableFilterDataProvider<BEAN, FILTER, FILTER>).setFilter(filter)
    }

    /**
     * Computes a filter from all currently registered filter components, but doesn't
     * set it into [currentFilter].
     */
    private fun computeFilter(): FILTER? {
        val filters: List<FILTER> = bindings.keys.mapNotNull { it.getFilter() }
        return filterFactory.and(filters.toSet())
    }

    /**
     * Recomputes the most current filter from all filter components. If the
     * filter differs from the current one, applies it to the grid.
     */
    private fun updateFilter() {
        val newFilter: FILTER? = computeFilter()
        if (newFilter != currentFilter) {
            applyFilterToGrid(newFilter)
            currentFilter = newFilter
        }
    }

    fun <VALUE : Any> forField(component: HasValue<*, VALUE?>, column: Grid.Column<BEAN>): Binding.Builder<BEAN, VALUE, FILTER> {
        require(!column.key.isNullOrBlank()) { "The column needs to have the property name as its key" }
        return Binding.Builder(filterFactory, this, column, component as Component) {
            // useless cast to keep Kotlin compiler happy
            (component as HasValue<HasValue.ValueChangeEvent<VALUE?>, VALUE?>).value
        }
    }

    class Binding<BEAN : Any, F : Any>(internal val builder: Builder<BEAN, F, F>) : Serializable {
        /**
         * Returns the current filter from the filter component.
         */
        fun getFilter(): F? = builder.valueGetter()

        /**
         * Registers a [filterChangeListener], fired when the filter is changed.
         * @return the listener registration; use [Registration.remove] to remove the listener.
         */
        fun addFilterChangeListener(filterChangeListener: () -> Unit) : Registration =
            (filterComponent as HasValue<*, *>).addValueChangeListener { filterChangeListener() }

        fun unbind() {
            builder.filterBar.unregisterBinding(this)
        }

        val filterComponent: Component get() = builder.filterComponent

        fun clearFilter() {
            (filterComponent as HasValue<*, *>).clear()
        }

        /**
         * Gradually builds the filter binding.
         * @param BEAN the type of bean present in the Grid
         * @param VALUE this binding is able to extract a value of this type out of the [filterComponent].
         * @param TARGETFILTER we ultimately aim to get the filter of this type out of the [filterComponent].
         * @param filterFactory creates filters of type [TARGETFILTER]
         * @param filterComponent the Vaadin filter component, ultimately placed into the [Grid]'s [HeaderRow].
         * @param valueGetter retrieves the current value of type [VALUE] from the [filterComponent].
         */
        class Builder<BEAN : Any, VALUE : Any, TARGETFILTER : Any>(
                val filterFactory: FilterFactory<TARGETFILTER>,
                internal val filterBar: FilterBar<BEAN, TARGETFILTER>,
                val column: Grid.Column<BEAN>,
                internal val filterComponent: Component,
                internal val valueGetter: () -> VALUE?) : Serializable {
            val propertyName: String get() = requireNotNull(column.key) { "The column needs to have the property name as its key" }

            /**
             * Adds a converter to the chain which converts the value from [filterComponent]
             * (or rather from the previous [valueGetter]).
             * @param closure never receives nulls nor blank strings: nulls are automatically short-circuited and converted to nulls of type [NEWVALUE].
             * @param NEWVALUE the new value produced by the new builder (which is now the head of the conversion chain).
             * @return the new converter chain head
             */
            fun <NEWVALUE : Any> withConverter(closure: (VALUE) -> NEWVALUE?): Builder<BEAN, NEWVALUE, TARGETFILTER> {
                val chainedValueGetter: () -> NEWVALUE? = {
                    val prevValue: VALUE? = valueGetter()
                    val isBlankString: Boolean = (prevValue as? String)?.isBlank() ?: false
                    if (prevValue == null || isBlankString) null else closure(prevValue)
                }
                return Builder(filterFactory, filterBar, column, filterComponent, chainedValueGetter)
            }

            fun eq(): Binding<BEAN, TARGETFILTER> = withConverter { filterFactory.eq(propertyName, it) } .bind()

            fun le(): Binding<BEAN, TARGETFILTER> = withConverter { filterFactory.le(propertyName, it) } .bind()

            fun ge(): Binding<BEAN, TARGETFILTER> = withConverter { filterFactory.ge(propertyName, it) } .bind()
        }
    }

    internal fun finalizeBinding(binding: Binding<BEAN, FILTER>) {
        val reg: Registration = binding.addFilterChangeListener { updateFilter() }
        bindings[binding] = reg
        updateFilter()
        configure(binding.builder.filterComponent)
        headerRow.getCell(binding.builder.column).setComponent(binding.builder.filterComponent)
    }

    private fun unregisterBinding(binding: Binding<BEAN, FILTER>) {
        headerRow.getCell(binding.builder.column).setComponent(null)
        bindings.remove(binding)?.remove()
        updateFilter()
    }

    /**
     * Clears all filter components, which effectively removes any filters.
     */
    fun clear() {
        bindings.keys.forEach { it.clearFilter() }
    }

    /**
     * Returns all current bindings.
     */
    fun getBindings(): List<Binding<BEAN, FILTER>> = bindings.keys.toList()

    /**
     * Removes all filter components from this filter bar.
     */
    fun removeAllBindings() {
        bindings.keys.toList().forEach { it.unbind() }
    }

    fun getBindingFor(column: Grid.Column<BEAN>): Binding<BEAN, FILTER> {
        val binding: Binding<BEAN, FILTER>? = bindings.keys.firstOrNull { it.builder.column == column }
        checkNotNull(binding) { "No binding for column ${column.key}: $column" }
        return binding
    }

    fun getBindingFor(property: KProperty1<BEAN, *>): Binding<BEAN, FILTER> =
            getBindingFor(grid.getColumnBy(property))

    /**
     * Configures every Vaadin UI filter [field]. By default the width is set to 100%
     * and the clear button is made visible for [TextField] and [ComboBox].
     */
    open protected fun configure(field: Component) {
        (field as? HasSize)?.width = "100%"
        (field as? TextField)?.isClearButtonVisible = true
        (field as? ComboBox<*>)?.isClearButtonVisible = true
        (field as? HasValueChangeMode)?.valueChangeMode = ValueChangeMode.LAZY
    }

    fun getFilterComponent(column: Grid.Column<BEAN>) = getBindingFor(column).filterComponent

    fun getFilterComponent(property: KProperty1<BEAN, *>) = getFilterComponent(grid.getColumnBy(property))
}

fun <BEAN : Any, FILTER: Any> FilterBar.Binding.Builder<BEAN, FILTER, FILTER>.bind(): FilterBar.Binding<BEAN, FILTER> {
    val binding: FilterBar.Binding<BEAN, FILTER> = FilterBar.Binding(this)
    filterBar.finalizeBinding(binding)
    return binding
}

fun <BEAN : Any, FILTER: Any> FilterBar.Binding.Builder<BEAN, String, FILTER>.ilike(): FilterBar.Binding<BEAN, FILTER> =
        withConverter { if (it.isBlank()) null else filterFactory.ilike(propertyName, it) }
                .bind()

@JvmName("numberIntervalInRange")
fun <BEAN : Any, FILTER: Any> FilterBar.Binding.Builder<BEAN, NumberInterval<Double>, FILTER>.inRange(): FilterBar.Binding<BEAN, FILTER> =
        withConverter { it.toFilter(propertyName, filterFactory) }
                .bind()

@JvmName("dateIntervalInRange")
fun <BEAN : Any, FILTER: Any> FilterBar.Binding.Builder<BEAN, DateInterval, FILTER>.inRange(fieldType: KClass<*>): FilterBar.Binding<BEAN, FILTER> =
        inRange(fieldType.java)

@JvmName("dateIntervalInRange2")
fun <BEAN : Any, FILTER: Any> FilterBar.Binding.Builder<BEAN, DateInterval, FILTER>.inRange(fieldType: Class<*>): FilterBar.Binding<BEAN, FILTER> =
        withConverter { it.toFilter(propertyName, filterFactory, fieldType) }
                .bind()

inline fun <BEAN : Any, FILTER: Any, reified V> FilterBar.Binding.Builder<BEAN, DateInterval, FILTER>.inRange(property: KProperty1<BEAN, V>): FilterBar.Binding<BEAN, FILTER> =
        inRange(V::class)

fun <BEAN : Any> FilterBar.Binding.Builder<BEAN, String, Filter<BEAN>>.fullText(): FilterBar.Binding<BEAN, Filter<BEAN>> =
        withConverter<Filter<BEAN>> { if (it.isBlank()) null else FullTextFilter<BEAN>(propertyName, it) }
                .bind()