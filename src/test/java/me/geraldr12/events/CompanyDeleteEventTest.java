package me.geraldr12.events;

import me.geraldr12.data.dao.CompanyDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompanyDeleteEventTest {

    @Test
    void singleArgumentConstructorIsSynchronous() {
        CompanyDao company = new CompanyDao();

        CompanyDeleteEvent event = new CompanyDeleteEvent(company);

        assertFalse(event.isAsynchronous());
        assertSame(company, event.getCompany());
    }

    @Test
    void explicitAsyncConstructorIsAsynchronous() {
        CompanyDao company = new CompanyDao();

        CompanyDeleteEvent event = new CompanyDeleteEvent(company, true);

        assertTrue(event.isAsynchronous());
        assertSame(company, event.getCompany());
    }

    @Test
    void explicitSyncConstructorIsSynchronous() {
        assertFalse(new CompanyDeleteEvent(new CompanyDao(), false).isAsynchronous());
    }
}
