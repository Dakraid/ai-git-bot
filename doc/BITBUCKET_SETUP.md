# Bitbucket Cloud Setup Guide

This guide explains how to configure the AI Code Review Bot to work with Bitbucket Cloud.

## Prerequisites

- A Bitbucket Cloud account
- Access to create API tokens in Bitbucket
- A repository where you want to enable the bot

## Step 1: Create an API Token

> **Note**: As of September 2025, Bitbucket Cloud has replaced App Passwords with API Tokens.
> Existing App Passwords will be disabled on June 9, 2026.

1. Go to your Atlassian account settings: https://id.atlassian.com/manage-profile/security/api-tokens
2. Click **Create API token**
3. Give it a label (e.g., "AI Code Review Bot")
4. Click **Create**
5. **Important**: Copy the generated token immediately - you won't be able to see it again!

The token will look like: `ATATT3xFfGF0CNndTrZZ...`

### Required Scopes

When creating the API token, ensure it has access to:
- Read repositories
- Read and write pull requests

## Step 2: Configure the Git Integration

In the bot's admin UI, create a new Git Integration with the following settings:

| Field | Value |
|-------|-------|
| **Name** | e.g., "Bitbucket Cloud" |
| **Provider Type** | BITBUCKET |
| **URL** | `https://bitbucket.org` or `https://api.bitbucket.org/2.0` |
| **Token** | Your API token (starting with `ATATT...`) |

### Token Format

The bot supports two authentication methods:

1. **API Tokens (recommended)**: Just paste the token starting with `ATATT...`
   ```
   ATATT3xFfGF0CNndTrZZuJdJfXcmNmuF2RQK9fTUUTRhThM...
   ```

2. **Legacy App Passwords** (deprecated, will stop working June 2026):
   ```
   username:app_password
   ```

## Step 3: Create a Bot

Create a new Bot in the admin UI and link it to:
- Your Bitbucket Git Integration
- Your AI Integration (e.g., Anthropic)

Note the **Webhook Secret** that is generated - you'll need this for the next step.

## Step 4: Configure the Webhook in Bitbucket

1. Go to your Bitbucket repository
2. Navigate to **Repository settings** → **Webhooks**
3. Click **Add webhook**
4. Configure the webhook:
   - **Title**: AI Code Review Bot
   - **URL**: `https://your-bot-server.com/api/webhook/{webhook_secret}`
     - Replace `{webhook_secret}` with your bot's webhook secret
   - **Triggers**: Select the following:
     - Pull request: Created
     - Pull request: Updated
     - Pull request: Comment created (optional, for bot commands)
5. Click **Save**

## Step 5: Test the Integration

1. Create a new Pull Request in your repository
2. The bot should automatically post a code review comment
3. If it doesn't work, check the bot's logs for error messages

## Troubleshooting

### Error: "401 Unauthorized: Token is invalid"
- Check that your token is in the format `username:app_password`
- Verify the app password hasn't expired
- Ensure the app password has the required permissions

### Error: "400 Bad Request: Invalid Authorization header"
- The token format is incorrect
- Make sure the token is `username:app_password` (with a colon)
- Don't include any extra spaces or characters

### Error: "Webhook ignored"
- Check that the webhook URL includes the correct webhook secret
- Verify the bot is enabled in the admin UI
- Check that the Git Integration's provider type is set to BITBUCKET

### Error: "No bot found for webhook secret"
- The webhook secret in the URL doesn't match any configured bot
- Verify the webhook URL in Bitbucket matches your bot's secret

## App Password Permissions

The minimum required permissions for the App Password are:

| Permission | Required For |
|------------|--------------|
| Repository: Read | Fetching PR diffs, reading file contents |
| Pull requests: Read | Reading PR information |
| Pull requests: Write | Posting review comments |

For agent features (implementing issues), you may also need:
- Repository: Write (for creating branches and commits)
- Pull requests: Write (for creating PRs)

