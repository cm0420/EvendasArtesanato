-- Um carrinho aberto por cliente: sem isso, dois "abrir carrinho" simultâneos
-- criavam dois e a busca do carrinho passava a falhar para sempre.
CREATE UNIQUE INDEX uq_orders_one_cart_per_customer ON orders (customer_id) WHERE status = 'CARRINHO';
