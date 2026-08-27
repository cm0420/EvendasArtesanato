-- Pendência aberta desde o módulo stock: agora que a tabela orders existe,
-- adiciona a constraint física que faltava. Nenhuma mudança de código Java
-- foi necessária — o campo order_id já existia como UUID solto.
ALTER TABLE stock_reservations
ADD CONSTRAINT fk_stock_reservations_order
FOREIGN KEY (order_id) REFERENCES orders(id);
