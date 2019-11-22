# Personalized New Relic Dashboard App in Slack

This tiny [Lightning](https://github.com/seratch/jslack/tree/master/jslack-lightning) ⚡️ app demonstrates how to build Slack apps utilizing Slack's new features and New Relic APIs. This app displays the list of New Relic applications and Slack users can choose one to check from the list. Also, users can run NRQL (New Relic Query Language) queries in modals (=without leaving Slack).

<img src="https://github.com/seratch/new-relic-dashboard-in-slack-kotlin/raw/master/images/new-relic-dashboard.gif" width=600 />

## App Home

Slack apps can leverage any of [Block Kit](https://api.slack.com/block-kit), [a collection of powerful built-in UI components](https://api.slack.com/tools/block-kit-builder?mode=appHome), to make
[Home Tab](https://api.slack.com/surfaces/tabs) not only informative, furthermore interactive. This app illustrates how to build a kind of dashboard UI and also place buttons and drop-down menus in a user-friendly manner.

<img src="https://github.com/seratch/new-relic-dashboard-in-slack-kotlin/raw/master/images/home.png" width=600 />

## Modals

Modals are dynamic and interactive space for collecting data from Slack users and displaying information. Needless to say, we can use [Block Kit](https://api.slack.com/tools/block-kit-builder?mode=modal) in modals. In addition, [Input blocks](https://api.slack.com/reference/block-kit/blocks#input) are available only in modals.

<img src="https://github.com/seratch/new-relic-dashboard-in-slack-kotlin/raw/master/images/query-modal.png" width=600 />

# Prerequisites

## Slack App

* Create a Slack App at https://api.slack.com/
* Sign up for App Home Beta (the feature is in the open beta as of Nov 2019)
* Enable bot scopes (`app_mentions:read`, `users:read`)
* Enable event subscriptions (`app_home_opened`, `app_mention`)
* Enable Interactive Components (Just set `Request URL` correctly and save the change)
* Install the App into your workspace

<img src="https://github.com/seratch/new-relic-dashboard-in-slack-kotlin/raw/master/images/settings-app-home.png" width=500 />
<img src="https://github.com/seratch/new-relic-dashboard-in-slack-kotlin/raw/master/images/settings-scopes.png" width=500 />
<img src="https://github.com/seratch/new-relic-dashboard-in-slack-kotlin/raw/master/images/settings-event-subscriptions.png" width=500 />

## New Relic

* REST API Key - `https://rpm.newrelic.com/accounts/{your account id}/integrations?page=api_keys`
* Insights API Key - `https://insights.newrelic.com/accounts/{your account id}/manage/api_keys`

# Local Development

```bash
# macOS
export JAVA_HOME=$(/usr/libexec/java_home -v 11) # 11 or higher

export SLACK_BOT_TOKEN=xoxb-xxx-yyy
export SLACK_SIGNING_SECRET=abc
./gradlew bootRun
```

```bash
docker build -t new-relic-jvm .
docker run -p 3000:3000 -it new-relic-jvm
```

# Deployment

If you prefer using a real database, feel free to fork this repository 👍

```bash
# macOS
export JAVA_HOME=$(/usr/libexec/java_home -v 11) # 11 or higher

./gradlew build
java -jar build/libs/new-relic-slack-app-home-*.jar
```

# Resources

* [Slack - Home Tab](https://api.slack.com/surfaces/tabs)
* [Slack - Modals](https://api.slack.com/block-kit/surfaces/modals)
* [New Relic REST API](https://docs.newrelic.com/docs/apis/rest-api-v2)
* [New Relic Insights API](https://docs.newrelic.com/docs/insights/insights-api)

# License

The MIT License
