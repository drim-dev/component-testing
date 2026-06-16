using FluentValidation;
using IdGen.DependencyInjection;
using MediatR;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Common.Identity;
using Relay.Api.Common.Validation;
using Amazon.Runtime;
using Amazon.S3;
using Confluent.Kafka;
using RabbitMQ.Client;
using Relay.Api.Database;
using Relay.Api.Features.Assistant;
using Relay.Api.Features.Attachments;
using Relay.Api.Features.Channels;
using Relay.Api.Features.Feed;
using Relay.Api.Features.Messages;
using Relay.Api.Features.Notifications;
using StackExchange.Redis;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddDbContext<AppDbContext>(options =>
    options.UseNpgsql(builder.Configuration.GetConnectionString("relay")));

builder.Services.AddSingleton<IConnectionMultiplexer>(_ =>
    ConnectionMultiplexer.Connect(builder.Configuration.GetConnectionString("redis")
        ?? throw new InvalidOperationException("Connection string 'redis' is required.")));

builder.Services.AddMediatR(cfg =>
{
    cfg.RegisterServicesFromAssemblyContaining<Program>();
    cfg.AddOpenBehavior(typeof(ValidationBehavior<,>));
});
builder.Services.AddValidatorsFromAssemblyContaining<Program>();

builder.Services.AddIdGen(builder.Configuration.GetValue<int?>("IdGen:GeneratorId") ?? 0);
builder.Services.AddSingleton<IdFactory>();

builder.Services.AddScoped<CurrentUser>();
builder.Services.AddScoped<IDmAccess, DmAccess>();
builder.Services.AddScoped<IConversationWriter, ConversationWriter>();
builder.Services.AddScoped<IChannelReadGate, ChannelReadGate>();
builder.Services.AddScoped<IChannelRoleGate, ChannelRoleGate>();
builder.Services.AddSingleton<MembershipCache>();
builder.Services.AddScoped<IMembershipWriter, MembershipWriter>();
builder.Services.AddSingleton<UnreadCounters>();

builder.Services.AddSingleton<IProducer<string, string>>(sp =>
{
    var logger = sp.GetRequiredService<ILogger<KafkaMessagePostedPublisher>>();
    var config = new ProducerConfig
    {
        BootstrapServers = builder.Configuration["Kafka:BootstrapServers"],
        Acks = Acks.All,
        // Fail fast enough for the pinned 503-on-broker-down behavior to be testable.
        MessageTimeoutMs = 5000,
    };
    return new ProducerBuilder<string, string>(config)
        .SetLogHandler((_, log) => logger.LogDebug("Kafka producer: {Message}", log.Message))
        .SetErrorHandler((_, error) => logger.LogDebug("Kafka producer error: {Reason}", error.Reason))
        .Build();
});
builder.Services.AddSingleton<IMessagePostedPublisher, KafkaMessagePostedPublisher>();
builder.Services.AddScoped<IFeedProjector, FeedProjector>();

builder.Services.AddSingleton<IConnection>(_ =>
{
    var factory = new ConnectionFactory
    {
        Uri = new Uri(builder.Configuration.GetConnectionString("rabbit")
            ?? throw new InvalidOperationException("Connection string 'rabbit' is required.")),
    };
    return factory.CreateConnectionAsync().GetAwaiter().GetResult();
});
builder.Services.AddSingleton<INotificationJobs, RabbitNotificationJobs>();
builder.Services.AddScoped<INotificationRecorder, NotificationRecorder>();

builder.Services.AddSingleton<IAmazonS3>(_ => new AmazonS3Client(
    new BasicAWSCredentials(builder.Configuration["S3:AccessKey"], builder.Configuration["S3:SecretKey"]),
    new AmazonS3Config
    {
        ServiceURL = builder.Configuration["S3:ServiceUrl"],
        ForcePathStyle = true,
    }));
builder.Services.AddSingleton<IAttachmentStore, S3AttachmentStore>();
builder.Services.AddScoped<IAttachmentAccess, AttachmentAccess>();

builder.Services.AddSingleton<ISummaryModel, NotConfiguredSummaryModel>();
builder.Services.AddScoped<ISummarizer, Summarizer>();

builder.Services.AddGrpcClient<Relay.Api.Features.Presence.Grpc.Presence.PresenceClient>(options =>
    options.Address = new Uri(builder.Configuration["Presence:Address"]
        ?? throw new InvalidOperationException("Configuration 'Presence:Address' is required.")));
builder.Services.AddScoped<Relay.Api.Features.Presence.IPresenceClient, Relay.Api.Features.Presence.PresenceClient>();

builder.Services.AddHttpClient<ILinkPreviewer, LinkPreviewer>();

// Worker role is a deployment knob: API-only hosts (and most naive-variant test hosts)
// run without the broker consumers.
if (builder.Configuration.GetValue("Workers:Enabled", true))
{
    builder.Services.AddHostedService<FeedFanoutConsumer>();
    builder.Services.AddHostedService<NotificationWorker>();
}

builder.Services.AddEndpoints(typeof(Program).Assembly);

var app = builder.Build();

app.UseMiddleware<ExceptionHandlerMiddleware>();
app.UseMiddleware<UserContextMiddleware>();
app.MapEndpoints();

app.Run();

public partial class Program;
