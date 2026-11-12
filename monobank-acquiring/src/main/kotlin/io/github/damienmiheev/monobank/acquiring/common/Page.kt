package io.github.damienmiheev.monobank.acquiring.common

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Paging information of a list answer.
 *
 * @property totalItems Number of items matching the query, on all pages.
 * @property itemsPerPage Page size.
 * @property currentPage Number of this page, starting at 1.
 * @property totalPages Number of pages.
 */
@ConsistentCopyVisibility
public data class Pagination
    @JsonCreator
    internal constructor(
        @param:JsonProperty("totalItems") public val totalItems: Int,
        @param:JsonProperty("itemsPerPage") public val itemsPerPage: Int,
        @param:JsonProperty("currentPage") public val currentPage: Int,
        @param:JsonProperty("totalPages") public val totalPages: Int,
    ) {
        /** `true` when there is a page after this one: request `page = currentPage + 1`. Java: `hasNextPage()`. */
        @get:JvmName("hasNextPage")
        public val hasNextPage: Boolean
            get() = currentPage < totalPages
    }

/**
 * One page of a paginated list.
 *
 * @property items Items on this page; empty when there are none.
 * @property pagination Paging information. `null` only if monobank omitted it.
 */
@ConsistentCopyVisibility
public data class Page<T> internal constructor(
    public val items: List<T>,
    public val pagination: Pagination?,
) {
    /** `true` when there is a page after this one. Java: `hasNextPage()`. */
    @get:JvmName("hasNextPage")
    public val hasNextPage: Boolean
        get() = pagination?.hasNextPage ?: false
}
