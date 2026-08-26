# Módulo `stock` — Teste via curl

```bash
#!/bin/bash
set -e

BASE_URL="http://localhost:8080"

echo "== 1. Login como ADMIN =="
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email": "joao@teste.com", "password": "senha12345"}')

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken')
echo "Token obtido: ${TOKEN:0:20}..."

echo ""
echo "== 2. Criar categoria de teste =="
CATEGORY_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/categories" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Teste Stock", "description": "categoria criada pelo script de teste"}')

CATEGORY_ID=$(echo "$CATEGORY_RESPONSE" | jq -r '.id')
echo "Categoria criada: $CATEGORY_ID"

echo ""
echo "== 3. Criar peça com stock: 3 =="
PIECE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/catalog/pieces" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Peça teste stock\", \"description\": \"peça de teste\", \"price\": 50.00, \"stock\": 3, \"imageUrls\": [], \"categoryIds\": [\"$CATEGORY_ID\"]}")

PIECE_ID=$(echo "$PIECE_RESPONSE" | jq -r '.id')
echo "Peça criada: $PIECE_ID (stock inicial: 3)"

echo ""
echo "== 4. Reservar 2 unidades =="
ORDER_ID_1=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')
RESERVATION_RESPONSE=$(curl -s -X POST "$BASE_URL/api/stock/reservations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 2, \"orderId\": \"$ORDER_ID_1\"}")

RESERVATION_ID=$(echo "$RESERVATION_RESPONSE" | jq -r '.id')
echo "Reserva criada: $RESERVATION_ID"
echo "$RESERVATION_RESPONSE" | jq

echo ""
echo "== 5. Conferir que o stock da peça caiu para 1 =="
curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{name, stock, inStock}'
echo "Esperado: stock = 1"

echo ""
echo "== 6. Tentar reservar 5 unidades (mais que o disponível) — deve dar 409 =="
ORDER_ID_2=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')
HTTP_STATUS=$(curl -s -o /tmp/stock_test_response.json -w "%{http_code}" -X POST "$BASE_URL/api/stock/reservations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 5, \"orderId\": \"$ORDER_ID_2\"}")

echo "Status recebido: $HTTP_STATUS (esperado: 409)"
cat /tmp/stock_test_response.json | jq

echo ""
echo "== 7. Liberar a reserva do passo 4 manualmente =="
curl -s -X POST "$BASE_URL/api/stock/reservations/$RESERVATION_ID/release" \
  -H "Authorization: Bearer $TOKEN" | jq

echo ""
echo "== 8. Conferir que o stock voltou para 3 =="
curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{name, stock, inStock}'
echo "Esperado: stock = 3 (voltou ao normal)"

echo ""
echo "== 9. Reservar de novo, dessa vez pra testar expiração automática (job) =="
ORDER_ID_3=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')
RESERVATION_2_RESPONSE=$(curl -s -X POST "$BASE_URL/api/stock/reservations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"pieceId\": \"$PIECE_ID\", \"quantity\": 1, \"orderId\": \"$ORDER_ID_3\"}")

RESERVATION_2_ID=$(echo "$RESERVATION_2_RESPONSE" | jq -r '.id')
echo "Reserva 2 criada: $RESERVATION_2_ID"
echo "Stock deve estar em 2 agora:"
curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{stock}'

echo ""
echo "Aguardando o job assíncrono expirar a reserva (~3-4 min, depende de"
echo "app.stock.reservation-expiration-minutes no application-dev.yml)"
sleep 200

echo ""
echo "== 10. Conferir se a reserva expirou sozinha e o stock voltou =="
curl -s "$BASE_URL/api/stock/reservations/$RESERVATION_2_ID" \
  -H "Authorization: Bearer $TOKEN" | jq
echo "Esperado: status = EXPIRED"

curl -s "$BASE_URL/api/catalog/pieces/$PIECE_ID" | jq '{stock}'
echo "Esperado: stock = 3 (o job devolveu sozinho)"

echo ""
echo "== Teste completo =="
```

## O que cada bloco valida

| Passo | O que confirma |
|---|---|
| 1–3 | Setup — login, categoria, peça com estoque conhecido |
| 4–5 | Reserva decrementa o `stock` da peça imediatamente |
| 6 | Reserva maior que o disponível é rejeitada com `409` |
| 7–8 | Liberação manual devolve a quantidade certinha |
| 9–10 | Job assíncrono encontra a reserva vencida sozinho e devolve o estoque |

**Não testa concorrência real** (duas requisições simultâneas disputando a mesma peça) — isso continua pendente, mesma situação dos outros módulos.
