export interface Ref {
  source_id: string;
  sheet: string | null;
  cell_range: string | null;
}
export interface Issue {
  code: string;
  message: string;
  severity?: string;
  product_id?: string;
  source_refs?: Ref[];
}
export interface Product {
  product_id: string;
  supplier_id: string;
  sku_1c: string;
  supplier_article: string | null;
  name: string;
  category_id: string | null;
  stock_unit: string;
  purchase_unit: string;
  purchase_unit_factor: number | null;
  moq_purchase_qty: number | null;
  pack_multiple_purchase_qty: number | null;
}
export interface Dataset {
  schema_version: string;
  name: string;
  data_kind: 'real' | 'synthetic';
  timezone: string;
  suppliers: { supplier_id: string; name: string }[];
  products: Product[];
  inventory: {
    product_id: string;
    warehouse_id: string;
    as_of: string;
    free_stock_qty: number | null;
  }[];
  sales_coverage: {
    warehouse_id: string;
    start_date: string;
    end_date_exclusive: string;
    complete: boolean;
  }[];
  sources: {
    source_id: string;
    file_name: string | null;
    role: string;
    note: string;
    correction?: CorrectionAudit;
  }[];
  issues: Issue[];
  reported_inbound?: {
    source_row_id: string;
    product_id: string;
    reported_quantity: number;
    unit: string | null;
    arrival_deadline: string | null;
    shipment_label: string;
    source_refs: Ref[];
  }[];
  reported_purchase_rules?: {
    source_row_id: string;
    product_id: string;
    rule_label: string;
    raw_value: string | null;
    reported_quantity: number | null;
    unit: string | null;
    source_refs: Ref[];
  }[];
}
export interface ProductCorrection {
  author: string;
  reason: string;
  purchase: {
    purchase_unit: string;
    purchase_unit_factor: number | null;
    category_id: string | null;
    moq_purchase_qty: number | null;
    pack_multiple_purchase_qty: number | null;
  } | null;
  stock: { warehouse_id: string; as_of: string; free_stock_qty: number } | null;
  supplier_policy: Params['supplier_policies'][number] | null;
}
export interface CorrectionAudit {
  parent_dataset_id: string;
  product_id: string;
  author: string;
  reason: string;
  created_at: string;
  before_product: Product;
  after_product: Product;
  before_stock: Dataset['inventory'][number] | null;
  after_stock: Dataset['inventory'][number] | null;
  supplier_policy: ProductCorrection['supplier_policy'];
}
export interface Summary {
  dataset_id: string;
  name: string;
  data_kind: 'real' | 'synthetic';
  status: string;
  counts: Record<string, number>;
  issue_count: number;
  issues: Issue[];
  created_at: string;
}
export interface Assumption {
  code: string;
  scope_id: string;
  value: boolean;
  reason: string;
  accepted_by: string;
}
export interface Params {
  dataset_id: string;
  as_of: string;
  warehouse_id: string;
  history_start: string;
  supplier_ids: string[];
  category_ids: string[];
  supplier_policies: {
    supplier_id: string;
    lead_time_days: number | null;
    review_period_days: number | null;
  }[];
  category_policies: {
    category_id: string;
    safety_days: number | null;
    purchasing_allowed: boolean;
  }[];
  forecast: {
    seasonality_mode: 'provided' | 'estimate';
    growth_mode: 'manual' | 'historical';
    manual_growth_pct: number | null;
    outlier_policy: string;
    stockout_mode: string;
  };
  assumptions: Assumption[];
}
export interface Item {
  item_id: string;
  product_id: string;
  supplier_id: string;
  sku_1c: string;
  supplier_article: string | null;
  name: string;
  category_id: string | null;
  stock_unit: string;
  purchase_unit: string;
  status: string;
  urgency: string;
  first_stockout_date: string | null;
  recommended_purchase_qty: number | null;
  recommended_stock_qty: number | null;
  final_purchase_qty: number | null;
  override: { quantity: number; reason: string } | null;
  factors: Record<string, number>;
  explanation: string;
  warnings: Issue[];
  assumptions: Assumption[];
  source_refs: Ref[];
  history: {
    date: string;
    actual_sales_qty: number;
    regular_sales_qty: number;
    estimated_lost_qty: number;
    availability_state: string;
  }[];
  forecast: {
    date: string;
    demand_qty: number;
    projected_stock_qty: number;
    unmet_demand_qty: number;
  }[];
  anomalies: {
    date: string;
    document_ids: string[];
    customer_id: string | null;
    original_qty: number;
    excluded_qty: number;
    reason: string;
  }[];
}
export interface Calculation {
  calculation_id: string;
  dataset_id: string;
  revision: number;
  status: string;
  data_kind: string;
  as_of: string;
  warehouse_id: string;
  methodology: { parameters: Params };
  items: Item[];
  supplier_groups: { supplier_id: string; item_ids: string[] }[];
  summary: { supplier_count: number; item_count: number; needs_input_count: number };
  approval: { approved_by: string; approved_at: string; revision: number } | null;
}
export interface Fixture {
  dataset: Dataset;
  calculation: Params;
}
