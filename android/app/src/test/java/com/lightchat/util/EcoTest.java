package com.lightchat.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EcoTest {
    @Test
    public void old_phone_is_eco() {
        assertEquals(Eco.MODE_ECO, Eco.mode(1, 512));
    }

    @Test
    public void modern_phone_is_standard() {
        assertEquals(Eco.MODE_STANDARD, Eco.mode(8, 6144));
    }

    @Test
    public void two_cores_falls_back_to_eco() {
        assertEquals(Eco.MODE_ECO, Eco.mode(2, 4096));
    }
}