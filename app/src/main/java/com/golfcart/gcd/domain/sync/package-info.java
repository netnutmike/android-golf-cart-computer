/**
 * Data synchronization domain.
 * <p>
 * Contains DataSyncManager which coordinates the end-to-end data flow:
 * incoming text messages → HoT packet identification → parsing → caching → ViewModel notification.
 * Manages the "(stored)" indicator for cached data and requests fresh data when cache is stale.
 */
package com.golfcart.gcd.domain.sync;
