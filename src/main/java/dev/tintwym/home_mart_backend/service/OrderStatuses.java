package dev.tintwym.home_mart_backend.service;

import java.util.List;
import java.util.Set;

/** Order lifecycle statuses used across checkout, sold checks, and UI. */
public final class OrderStatuses {

    private OrderStatuses() {}

    public static final String PENDING = "pending";
    /** Soft hold while Stripe Checkout or C2C arrange page is open (test purchases included). */
    public static final String RESERVED = "reserved";
    public static final String ARRANGED = "arranged";
    public static final String PAID = "paid";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    /** Listing must not be re-sold / re-reserved. */
    public static final List<String> SOLD_OR_HELD = List.of(PAID, COMPLETED, ARRANGED, RESERVED);

    /** Shown on the buyer orders page. */
    public static final Set<String> BUYER_VISIBLE = Set.of(PAID, COMPLETED, ARRANGED, RESERVED);
}
