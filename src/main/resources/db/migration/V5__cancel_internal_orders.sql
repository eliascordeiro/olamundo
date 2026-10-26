ALTER TABLE sales_orders
    ADD COLUMN cancelled_by VARCHAR(255),
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancellation_reason VARCHAR(500),
    DROP CONSTRAINT sales_orders_status_check,
    DROP CONSTRAINT sales_orders_check;

ALTER TABLE sales_orders
    ADD CONSTRAINT sales_orders_status_check CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    ADD CONSTRAINT sales_orders_lifecycle_check CHECK (
        (status = 'DRAFT' AND confirmed_at IS NULL AND confirmed_by IS NULL)
        OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)
        OR (status = 'CANCELLED' AND (
            (confirmed_at IS NULL AND confirmed_by IS NULL)
            OR (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)
        ))
    ),
    ADD CONSTRAINT sales_orders_cancellation_check CHECK (
        (status <> 'CANCELLED' AND cancelled_at IS NULL AND cancelled_by IS NULL AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
            AND cancellation_reason IS NOT NULL AND length(btrim(cancellation_reason)) BETWEEN 2 AND 500)
    );
