-- Immutable imported datasets; each source row is independently queryable.
CREATE TABLE datasets (
    dataset_id text PRIMARY KEY,
    metadata jsonb NOT NULL,
    summary jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE suppliers (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    supplier_id text GENERATED ALWAYS AS (payload->>'supplier_id') STORED,
    name text GENERATED ALWAYS AS (payload->>'name') STORED,
    PRIMARY KEY (dataset_id, position),
    UNIQUE (dataset_id, supplier_id)
);

CREATE TABLE products (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    supplier_id text GENERATED ALWAYS AS (payload->>'supplier_id') STORED,
    sku_1c text GENERATED ALWAYS AS (payload->>'sku_1c') STORED,
    name text GENERATED ALWAYS AS (payload->>'name') STORED,
    category_id text GENERATED ALWAYS AS (payload->>'category_id') STORED,
    stock_unit text GENERATED ALWAYS AS (payload->>'stock_unit') STORED,
    purchase_unit text GENERATED ALWAYS AS (payload->>'purchase_unit') STORED,
    purchase_unit_factor numeric GENERATED ALWAYS AS ((payload->>'purchase_unit_factor')::numeric) STORED,
    moq_purchase_qty numeric GENERATED ALWAYS AS ((payload->>'moq_purchase_qty')::numeric) STORED,
    pack_multiple_purchase_qty numeric GENERATED ALWAYS AS ((payload->>'pack_multiple_purchase_qty')::numeric) STORED,
    PRIMARY KEY (dataset_id, position),
    UNIQUE (dataset_id, product_id),
    UNIQUE (dataset_id, supplier_id, sku_1c),
    FOREIGN KEY (dataset_id, supplier_id) REFERENCES suppliers(dataset_id, supplier_id)
);

CREATE TABLE sales (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    sale_id text GENERATED ALWAYS AS (payload->>'sale_id') STORED,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    sale_date date GENERATED ALWAYS AS (make_date(substring(payload->>'date',1,4)::integer,substring(payload->>'date',6,2)::integer,substring(payload->>'date',9,2)::integer)) STORED,
    document_id text GENERATED ALWAYS AS (payload->>'document_id') STORED,
    customer_id text GENERATED ALWAYS AS (payload->>'customer_id') STORED,
    operation_type text GENERATED ALWAYS AS (payload->>'operation_type') STORED,
    quantity numeric GENERATED ALWAYS AS ((payload->>'quantity')::numeric) STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id),
    UNIQUE (dataset_id, sale_id),
    CHECK (quantity > 0)
);

CREATE TABLE inventory (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    as_of date GENERATED ALWAYS AS (make_date(substring(payload->>'as_of',1,4)::integer,substring(payload->>'as_of',6,2)::integer,substring(payload->>'as_of',9,2)::integer)) STORED,
    free_stock_qty numeric GENERATED ALWAYS AS ((payload->>'free_stock_qty')::numeric) STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id)
);

CREATE TABLE inbound (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    shipment_id text GENERATED ALWAYS AS (payload->>'shipment_id') STORED,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    quantity numeric GENERATED ALWAYS AS ((payload->>'quantity')::numeric) STORED,
    expected_date date GENERATED ALWAYS AS (make_date(substring(payload->>'expected_date',1,4)::integer,substring(payload->>'expected_date',6,2)::integer,substring(payload->>'expected_date',9,2)::integer)) STORED,
    status text GENERATED ALWAYS AS (payload->>'status') STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id),
    UNIQUE (dataset_id, shipment_id)
);

CREATE TABLE sales_coverage (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id)
);

CREATE TABLE inbound_coverage (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id)
);

CREATE TABLE availability (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id)
);

CREATE TABLE seasonality_profiles (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    PRIMARY KEY (dataset_id, position)
);

CREATE TABLE sources (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    PRIMARY KEY (dataset_id, position)
);

CREATE TABLE issues (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    PRIMARY KEY (dataset_id, position)
);

CREATE TABLE monthly_sales (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    month text GENERATED ALWAYS AS (payload->>'month') STORED,
    quantity numeric GENERATED ALWAYS AS ((payload->>'quantity')::numeric) STORED,
    meaning text GENERATED ALWAYS AS (payload->>'meaning') STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id)
);

CREATE TABLE monthly_stock (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    warehouse_id text GENERATED ALWAYS AS (payload->>'warehouse_id') STORED,
    month text GENERATED ALWAYS AS (payload->>'month') STORED,
    quantity numeric GENERATED ALWAYS AS ((payload->>'quantity')::numeric) STORED,
    meaning text GENERATED ALWAYS AS (payload->>'meaning') STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id)
);

CREATE TABLE reported_inbound (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    source_row_id text GENERATED ALWAYS AS (payload->>'source_row_id') STORED,
    reported_quantity numeric GENERATED ALWAYS AS ((payload->>'reported_quantity')::numeric) STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id),
    UNIQUE (dataset_id, source_row_id)
);

CREATE TABLE reported_purchase_rules (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    source_row_id text GENERATED ALWAYS AS (payload->>'source_row_id') STORED,
    reported_quantity numeric GENERATED ALWAYS AS ((payload->>'reported_quantity')::numeric) STORED,
    PRIMARY KEY (dataset_id, position),
    FOREIGN KEY (dataset_id, product_id) REFERENCES products(dataset_id, product_id),
    UNIQUE (dataset_id, source_row_id)
);

CREATE INDEX sales_product_date_idx ON sales(dataset_id, product_id, warehouse_id, sale_date);
CREATE INDEX inventory_product_idx ON inventory(dataset_id, product_id, warehouse_id, as_of);
CREATE INDEX inbound_product_date_idx ON inbound(dataset_id, product_id, expected_date);
CREATE INDEX monthly_sales_product_idx ON monthly_sales(dataset_id, product_id, month);
CREATE INDEX monthly_stock_product_idx ON monthly_stock(dataset_id, product_id, month);

-- Calculation header and individual items are persisted in the same transaction.
CREATE TABLE calculations (
    calculation_id text PRIMARY KEY,
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    revision integer NOT NULL CHECK (revision > 0),
    status text NOT NULL CHECK (status IN ('DRAFT', 'APPROVED')),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE calculation_items (
    calculation_id text NOT NULL REFERENCES calculations(calculation_id),
    position integer NOT NULL CHECK (position >= 0),
    payload jsonb NOT NULL,
    item_id text GENERATED ALWAYS AS (payload->>'item_id') STORED,
    product_id text GENERATED ALWAYS AS (payload->>'product_id') STORED,
    supplier_id text GENERATED ALWAYS AS (payload->>'supplier_id') STORED,
    final_purchase_qty numeric GENERATED ALWAYS AS ((payload->>'final_purchase_qty')::numeric) STORED,
    PRIMARY KEY (calculation_id, position),
    UNIQUE (calculation_id, item_id)
);
CREATE INDEX calculation_items_supplier_idx ON calculation_items(calculation_id, supplier_id);
CREATE TABLE calculation_events (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    calculation_id text NOT NULL REFERENCES calculations(calculation_id),
    revision integer NOT NULL,
    action text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    details jsonb NOT NULL
);
CREATE INDEX calculation_events_history_idx ON calculation_events(calculation_id,event_id);
CREATE TABLE source_files (
    dataset_id text NOT NULL REFERENCES datasets(dataset_id),
    part_name text NOT NULL,
    file_name text NOT NULL,
    object_key text NOT NULL,
    sha256 text NOT NULL,
    byte_size bigint NOT NULL CHECK (byte_size >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (dataset_id, part_name, sha256)
);
