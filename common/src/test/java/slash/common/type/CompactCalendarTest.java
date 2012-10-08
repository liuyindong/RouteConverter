/*
    This file is part of RouteConverter.

    RouteConverter is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    RouteConverter is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with RouteConverter; if not, write to the Free Software
    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA

    Copyright (C) 2007 Christian Pesch. All Rights Reserved.
*/

package slash.common.type;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static slash.common.TestCase.assertCalendarEquals;
import static slash.common.type.CompactCalendar.UTC;

public class CompactCalendarTest {

    @Test
    public void fromMillis() {
        Calendar calendar = Calendar.getInstance(UTC);
        calendar.setTimeInMillis(1000);
        assertEquals(1000, calendar.getTimeInMillis());
        assertEquals(UTC, calendar.getTimeZone());

        CompactCalendar compactCalendar = CompactCalendar.fromMillis(1000);

        assertEquals(compactCalendar.getCalendar().getTimeInMillis(), 1000);
        assertCalendarEquals(calendar, compactCalendar.getCalendar());
        assertEquals(UTC, calendar.getTimeZone());
    }

    @Test
    public void beforeAndAfter() {
        CompactCalendar early = CompactCalendar.fromMillis(1000);
        CompactCalendar late = CompactCalendar.fromMillis(2000);

        assertTrue(early.before(late));
        assertFalse(late.before(early));

        assertFalse(early.after(late));
        assertTrue(late.after(early));

        assertFalse(early.before(early));
        assertFalse(early.after(early));
    }
}
