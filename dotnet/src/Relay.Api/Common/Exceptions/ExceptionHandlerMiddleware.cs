using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace Relay.Api.Common.Exceptions;

/// <summary>
/// Translates every <see cref="ApiException"/> into the pinned error body
/// (<c>{ status, code, message }</c>, see spec/02-api.md §0). Existence-hiding works
/// because unknown-id and unauthorized paths throw the SAME code+message, so the JSON
/// is byte-identical — a property the gallery's 404 catches assert directly.
/// </summary>
public sealed class ExceptionHandlerMiddleware(RequestDelegate next, ILogger<ExceptionHandlerMiddleware> logger)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task InvokeAsync(HttpContext context)
    {
        try
        {
            await next(context);
        }
        catch (ApiException ex)
        {
            await WriteError(context, ex.Status, ex.Code, ex.Message);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Unhandled exception");
            await WriteError(context, 500, "internal:error", "An unexpected error occurred.");
        }
    }

    private static async Task WriteError(HttpContext context, int status, string code, string message)
    {
        context.Response.Clear();
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json; charset=utf-8";
        var body = JsonSerializer.Serialize(new ErrorBody(status, code, message), JsonOptions);
        await context.Response.WriteAsync(body);
    }

    private sealed record ErrorBody(int Status, string Code, string Message);
}
