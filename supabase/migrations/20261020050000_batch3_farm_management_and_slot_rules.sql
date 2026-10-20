    RETURN jsonb_build_object(
        'success', TRUE,
        'farm_id', v_farm.id,
        'name', v_farm.name,
        'status', v_farm.status,
        'goat_listing_limit', v_farm.goat_listing_limit,
        'consumed_listing_slots', v_farm.consumed_listing_slots,
        'updated_at', v_farm.updated_at
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) FROM anon;
GRANT EXECUTE ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) TO authenticated;

-- Use actual enum values and columns from this project. The live schema has
-- farms.status = PENDING/APPROVED/REJECTED/SUSPENDED and goats.status.
DROP POLICY IF EXISTS goats_insert_policy ON public.goats;
CREATE POLICY goats_insert_policy ON public.goats
FOR INSERT TO authenticated
WITH CHECK (
    EXISTS (
        SELECT 1
        FROM public.farms f
        WHERE f.id = goats.farm_id
          AND f.status = 'APPROVED'::public.farm_status
          AND (
              f.owner_id = auth.uid()
              OR public.is_super_admin()
          )
    )
);

DROP POLICY IF EXISTS goats_update_policy ON public.goats;
CREATE POLICY goats_update_policy ON public.goats
FOR UPDATE TO authenticated
USING (
    EXISTS (
        SELECT 1
        FROM public.farms f
        WHERE f.id = goats.farm_id
          AND f.status <> 'SUSPENDED'::public.farm_status
          AND (
              f.owner_id = auth.uid()
              OR public.is_super_admin()
          )
    )
)
WITH CHECK (
    EXISTS (
        SELECT 1
        FROM public.farms f
        WHERE f.id = goats.farm_id
          AND f.status <> 'SUSPENDED'::public.farm_status
          AND (
              f.owner_id = auth.uid()
              OR public.is_super_admin()
          )
    )
);
