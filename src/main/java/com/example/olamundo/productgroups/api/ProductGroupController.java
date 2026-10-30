package com.example.olamundo.productgroups.api;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.olamundo.auth.AuthService;
import com.example.olamundo.productgroups.application.ProductGroupService;
import com.example.olamundo.productgroups.application.ProductGroupService.GroupWithSubgroups;
import com.example.olamundo.productgroups.domain.GroupData;
import com.example.olamundo.productgroups.domain.ProductSubgroup;
import com.example.olamundo.productgroups.domain.SubgroupData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/product-groups")
@Tag(name = "Grupos de produtos", description = "Grupos e subgrupos da empresa; qualquer membro consulta, OWNER e MANAGER editam.")
public class ProductGroupController {
	private final ProductGroupService groups;
	private final AuthService accounts;

	public ProductGroupController(ProductGroupService groups, AuthService accounts) {
		this.groups = groups;
		this.accounts = accounts;
	}

	@GetMapping
	@Operation(summary = "Listar todos os grupos da empresa, cada um com seus subgrupos; qualquer membro da loja")
	public List<GroupView> list(Principal principal, @PathVariable UUID storeId) {
		return groups.list(storeId, userId(principal)).stream().map(GroupView::from).toList();
	}

	@PostMapping
	@Operation(summary = "Cadastrar grupo, com reenvio seguro por requestId; OWNER ou MANAGER")
	@SecurityRequirement(name = "csrf")
	public GroupView createGroup(Principal principal, @PathVariable UUID storeId, @Valid @RequestBody NewGroup request) {
		return GroupView.from(groups.createGroup(storeId, userId(principal), request.requestId(), request.data()));
	}

	@PostMapping("/{groupId}")
	@Operation(summary = "Editar grupo por versao; OWNER ou MANAGER")
	@SecurityRequirement(name = "csrf")
	public GroupView updateGroup(Principal principal, @PathVariable UUID storeId, @PathVariable UUID groupId,
			@Valid @RequestBody EditGroup request) {
		return GroupView.from(groups.updateGroup(storeId, userId(principal), groupId, request.version(), request.data()));
	}

	@PostMapping("/{groupId}/subgroups")
	@Operation(summary = "Cadastrar subgrupo, com reenvio seguro por requestId; OWNER ou MANAGER")
	@SecurityRequirement(name = "csrf")
	public ProductSubgroup createSubgroup(Principal principal, @PathVariable UUID storeId, @PathVariable UUID groupId,
			@Valid @RequestBody NewSubgroup request) {
		return groups.createSubgroup(storeId, userId(principal), groupId, request.requestId(), request.data());
	}

	@PostMapping("/{groupId}/subgroups/{id}")
	@Operation(summary = "Editar subgrupo por versao; OWNER ou MANAGER")
	@SecurityRequirement(name = "csrf")
	public ProductSubgroup updateSubgroup(Principal principal, @PathVariable UUID storeId, @PathVariable UUID groupId,
			@PathVariable UUID id, @Valid @RequestBody EditSubgroup request) {
		return groups.updateSubgroup(storeId, userId(principal), groupId, id, request.version(), request.data());
	}

	private String userId(Principal principal) {
		return accounts.currentUser(principal.getName()).id();
	}

	public record GroupView(UUID id, String name, String ncm, BigDecimal commission, BigDecimal assemblyCommission,
			BigDecimal assemblyDiscount, BigDecimal markup, BigDecimal pautaDiscount, BigDecimal flexibleDiscount,
			long version, Instant updatedAt, List<ProductSubgroup> subgroups) {
		static GroupView from(GroupWithSubgroups value) {
			var g = value.group();
			return new GroupView(g.id(), g.name(), g.ncm(), g.commission(), g.assemblyCommission(), g.assemblyDiscount(),
					g.markup(), g.pautaDiscount(), g.flexibleDiscount(), g.version(), g.updatedAt(), value.subgroups());
		}
	}

	public record NewGroup(@NotNull UUID requestId, @NotBlank @Size(max = 100) String name, @Size(max = 40) String ncm,
			BigDecimal commission, BigDecimal assemblyCommission, BigDecimal assemblyDiscount, BigDecimal markup,
			BigDecimal pautaDiscount, BigDecimal flexibleDiscount) {
		GroupData data() {
			return new GroupData(name, ncm, commission, assemblyCommission, assemblyDiscount, markup, pautaDiscount,
					flexibleDiscount);
		}
	}

	public record EditGroup(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 100) String name,
			@Size(max = 40) String ncm, BigDecimal commission, BigDecimal assemblyCommission, BigDecimal assemblyDiscount,
			BigDecimal markup, BigDecimal pautaDiscount, BigDecimal flexibleDiscount) {
		GroupData data() {
			return new GroupData(name, ncm, commission, assemblyCommission, assemblyDiscount, markup, pautaDiscount,
					flexibleDiscount);
		}
	}

	public record NewSubgroup(@NotNull UUID requestId, @NotBlank @Size(max = 100) String name, BigDecimal markup,
			BigDecimal margin, BigDecimal pautaDiscount, BigDecimal flexibleDiscount) {
		SubgroupData data() {
			return new SubgroupData(name, markup, margin, pautaDiscount, flexibleDiscount);
		}
	}

	public record EditSubgroup(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 100) String name,
			BigDecimal markup, BigDecimal margin, BigDecimal pautaDiscount, BigDecimal flexibleDiscount) {
		SubgroupData data() {
			return new SubgroupData(name, markup, margin, pautaDiscount, flexibleDiscount);
		}
	}
}
