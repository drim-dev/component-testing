using System.Linq.Expressions;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;

namespace Relay.Api.Common.Pagination;

/// <summary>
/// The strict pagination contract (spec/02-api.md §0.1): <c>limit</c> 1–100 (default 50),
/// validated — never clamped. This bound is the deterministic pin the §3
/// "weakened validation" gaming story violates, so the 422s here are exact.
/// </summary>
public static class Paging
{
    public const int DefaultLimit = 50;
    public const int MinLimit = 1;
    public const int MaxLimit = 100;

    public static int ParseLimit(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return DefaultLimit;
        }

        if (!int.TryParse(raw, out var limit))
        {
            throw new ValidationException("pagination:limit:not_a_number", "limit must be an integer.");
        }

        if (limit < MinLimit || limit > MaxLimit)
        {
            throw new ValidationException(
                "pagination:limit:out_of_range",
                $"limit must be between {MinLimit} and {MaxLimit}.");
        }

        return limit;
    }

    /// <summary>
    /// Newest-first keyset pagination over an authorized scope, ordered by the opaque
    /// (time-ordered) id. An unknown <c>before</c> cursor → 422 — the deterministic pin
    /// the weakened-validation case violates.
    /// </summary>
    public static async Task<PageResponse<TDto>> Page<TEntity, TDto>(
        IQueryable<TEntity> scope,
        Expression<Func<TEntity, string>> idSelector,
        Expression<Func<TEntity, TDto>> projection,
        Func<TDto, string> dtoId,
        string? before,
        int limit,
        CancellationToken ct)
    {
        var parameter = idSelector.Parameters[0];

        if (before is not null)
        {
            var equals = Expression.Lambda<Func<TEntity, bool>>(
                Expression.Equal(idSelector.Body, Expression.Constant(before, typeof(string))),
                parameter);
            var exists = await scope.AnyAsync(equals, ct);
            if (!exists)
            {
                throw new ValidationException("pagination:before:unknown", "The before cursor is unknown.");
            }

            var compare = Expression.Call(
                typeof(string),
                nameof(string.Compare),
                Type.EmptyTypes,
                idSelector.Body,
                Expression.Constant(before, typeof(string)));
            var older = Expression.Lambda<Func<TEntity, bool>>(
                Expression.LessThan(compare, Expression.Constant(0)),
                parameter);
            scope = scope.Where(older);
        }

        var page = await scope
            .OrderByDescending(idSelector)
            .Select(projection)
            .Take(limit + 1)
            .ToListAsync(ct);

        var hasMore = page.Count > limit;
        var items = hasMore ? page.Take(limit).ToList() : page;
        var nextBefore = hasMore ? dtoId(items[^1]) : null;

        return new PageResponse<TDto>(items, nextBefore);
    }
}

public sealed record PageResponse<T>(IReadOnlyList<T> Items, string? NextBefore);
