# Plotted :: developer entry points
#
# One command must bring up a working, migrated system. Anything that needs a
# README paragraph to run belongs in here instead.

SHELL := /bin/sh
COMPOSE := docker compose
GRADLE := ./gradlew

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

# --- Development ----------------------------------------------------------

.PHONY: dev
dev: infra ## Start the backing services and print the URLs
	@echo ""
	@echo "  Plotted is ready. Flyway applies migrations when the API starts."
	@echo "    API          http://localhost:8080   (make api)"
	@echo "    Web          http://localhost:4200   (make web)"
	@echo "    API docs     http://localhost:8080/swagger-ui.html"
	@echo "    Health       http://localhost:8080/actuator/health"
	@echo "    Postgres     postgresql://plotted:plotted@localhost:5432/plotted"
	@echo ""

.PHONY: infra
infra: ## Start the backing services only
	$(COMPOSE) up -d postgres redis

.PHONY: api
api: ## Run the API from source with live reload
	$(GRADLE) :plotted-api:bootRun

.PHONY: web
web: ## Run the Angular dev server (proxies /api to localhost:8080)
	cd plotted-web && npm start

.PHONY: stack
stack: ## Run everything in containers, API and web included
	$(COMPOSE) --profile full up -d --build

.PHONY: workflows
workflows: ## Start Temporal and its UI (phase 10)
	$(COMPOSE) --profile workflows up -d

.PHONY: observability
observability: ## Start Prometheus and Grafana (phase 11)
	$(COMPOSE) --profile observability up -d

.PHONY: down
down: ## Stop everything, keeping the database volume
	$(COMPOSE) --profile full --profile workflows --profile observability down

.PHONY: reset
reset: ## Stop everything and destroy the database volume
	$(COMPOSE) --profile full --profile workflows --profile observability down -v

# --- Database -------------------------------------------------------------

.PHONY: psql
psql: ## Open a psql shell against the development database
	$(COMPOSE) exec postgres psql -U plotted -d plotted

.PHONY: seed
seed: ## Ingest the curated Canadian seed set (needs the database and a TMDB token)
	@test -n "$$TMDB_READ_ACCESS_TOKEN" || \
		(echo "Set TMDB_READ_ACCESS_TOKEN first. See docs/data-sources.md."; exit 2)
	$(GRADLE) :plotted-api:bootRun --args='--plotted.catalogue.seed.enabled=true'

# --- Build and verify -----------------------------------------------------

.PHONY: build
build: ## Compile and package both applications
	$(GRADLE) build -x test
	cd plotted-web && npm run build

.PHONY: test
test: ## Run every test. Container-backed tests need Docker.
	$(GRADLE) :plotted-api:test
	cd plotted-web && npm run test:ci

.PHONY: lint
lint: ## Static analysis for both applications
	$(GRADLE) ktlintCheck
	cd plotted-web && npm run lint

.PHONY: format
format: ## Apply formatting fixes
	$(GRADLE) ktlintFormat

.PHONY: verify
verify: lint test ## What CI runs on a pull request

.PHONY: premise-check
premise-check: ## Appendix A day one: does TMDB have usable Canadian availability?
	@test -n "$$TMDB_READ_ACCESS_TOKEN" || \
		(echo "Set TMDB_READ_ACCESS_TOKEN first. See docs/data-sources.md."; exit 2)
	$(GRADLE) :plotted-api:premiseCheck

.PHONY: check-env
check-env: ## Would a deploy boot, and would it do anything useful?
	@ops/deploy/check-env.sh

.PHONY: deploy
deploy: ## Deploy the API to Cloud Run. Run the database preflight first.
	@ops/deploy/deploy.sh

.PHONY: verify-deploy
verify-deploy: ## Check a deployed API. Usage: make verify-deploy HOST=https://...
	@test -n "$(HOST)" || (echo "Usage: make verify-deploy HOST=https://your-api-host"; exit 2)
	@ops/deploy/verify.sh "$(HOST)"

.PHONY: openapi
openapi: ## Regenerate the committed OpenAPI document (needs Docker)
	$(GRADLE) :plotted-api:test --tests '*OpenApiContractTest*' -Dplotted.openapi.write=true

# The same document, taken from an API you already have running, for machines
# with no Docker. `--verify` runs first: it re-renders the committed file from
# its own contents and refuses if the result differs, so a formatter that has
# drifted cannot quietly rewrite the document into a shape CI rejects.
.PHONY: openapi-local
openapi-local: ## Regenerate the OpenAPI document from a running API (no Docker)
	node tools/openapi/regenerate.mjs --verify
	node tools/openapi/regenerate.mjs

.PHONY: api-client
api-client: openapi ## Regenerate the Angular client from the OpenAPI document
	cd plotted-web && npm run generate:api
