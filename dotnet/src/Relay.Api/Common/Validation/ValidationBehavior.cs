using FluentValidation;
using MediatR;
using ValidationException = Relay.Api.Common.Exceptions.ValidationException;

namespace Relay.Api.Common.Validation;

/// <summary>
/// Runs FluentValidation before each handler. A failure becomes a 422 carrying the
/// rule's pinned code (spec uses 422, not 400 — strict validation, no clamping).
/// </summary>
public sealed class ValidationBehavior<TRequest, TResponse>(IEnumerable<IValidator<TRequest>> validators)
    : IPipelineBehavior<TRequest, TResponse>
    where TRequest : notnull
{
    public async Task<TResponse> Handle(
        TRequest request,
        RequestHandlerDelegate<TResponse> next,
        CancellationToken cancellationToken)
    {
        if (validators.Any())
        {
            var context = new ValidationContext<TRequest>(request);
            var results = await Task.WhenAll(
                validators.Select(v => v.ValidateAsync(context, cancellationToken)));
            var failure = results
                .SelectMany(r => r.Errors)
                .FirstOrDefault(f => f is not null);

            if (failure is not null)
            {
                var code = string.IsNullOrEmpty(failure.ErrorCode)
                    ? "validation:failed"
                    : failure.ErrorCode;
                throw new ValidationException(code, failure.ErrorMessage);
            }
        }

        return await next(cancellationToken);
    }
}
