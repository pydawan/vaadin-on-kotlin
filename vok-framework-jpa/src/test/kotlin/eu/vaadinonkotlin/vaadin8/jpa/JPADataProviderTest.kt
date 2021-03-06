package eu.vaadinonkotlin.vaadin8.jpa

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import com.github.mvysny.karibudsl.v8.addColumnFor
import com.github.mvysny.karibudsl.v8.grid
import com.github.mvysny.kaributesting.v8.MockVaadin
import com.github.mvysny.kaributesting.v8.expectRow
import com.github.mvysny.kaributesting.v8.expectRows
import com.vaadin.data.provider.Query
import com.vaadin.data.provider.QuerySortOrder
import com.vaadin.ui.UI
import kotlin.streams.toList
import kotlin.test.expect

class JPADataProviderTest : DynaTest({

    usingDatabase()

    test("noEntities") {
        val ds = jpaDataProvider<TestPerson>()
        expect(0) { ds.size(Query()) }
        expect(false) { ds.isInMemory }
        expectList() { ds.fetch(Query()).toList() }
    }

    test("sorting") {
        val ds = jpaDataProvider<TestPerson>()
        db { for (i in 15..90) em.persist(TestPerson(name = "test$i", age = i)) }
        expect(76) { ds.size(Query()) }
        expect((90 downTo 15).toList()) { ds.fetch(Query(0, 100, QuerySortOrder.desc("age").build(), null, null)).toList().map { it.age!! } }
    }

    test("filter") {
        db { for (i in 15..90) em.persist(TestPerson(name = "test$i", age = i)) }
        val ds = jpaDataProvider<TestPerson>().and { TestPerson::age between 30..60 }
        expect(31) { ds.size(Query()) }
        expect((30..60).toList()) { ds.fetch(Query(0, 100, QuerySortOrder.asc("age").build(), null, null)).toList().map { it.age!! } }
    }

    test("paging") {
        db { for (i in 15..90) em.persist(TestPerson(name = "test$i", age = i)) }
        val ds = jpaDataProvider<TestPerson>().and { TestPerson::age between 30..60 }
        expect((30..39).toList()) { ds.fetch(Query(0, 10, QuerySortOrder.asc("age").build(), null, null)).toList().map { it.age!! } }
        expect((40..49).toList()) { ds.fetch(Query(10, 10, QuerySortOrder.asc("age").build(), null, null)).toList().map { it.age!! } }
    }

    group("Vaadin Grid") {
        beforeEach { MockVaadin.setup() }
        afterEach { MockVaadin.tearDown() }
        test("grid") {
            db { for (i in 15..20) em.persist(TestPerson(name = "test$i", age = i)) }
            val grid = UI.getCurrent().grid<TestPerson> {
                dataProvider = jpaDataProvider<TestPerson>()
                addColumnFor(TestPerson::name)
                addColumnFor(TestPerson::age)
            }
            grid.expectRows(6)
            grid.expectRow(0, "test15", "15")
        }
    }
})
