URL ?= http://127.0.0.1:8095

.PHONY: build up down logs saude teste

build:
	docker compose build

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f pva

saude:
	@curl -s $(URL)/saude; echo

teste:
	URL=$(URL) ./scripts/teste-fumaca.sh
