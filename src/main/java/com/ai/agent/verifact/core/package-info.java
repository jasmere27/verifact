/**
 * Evidence Intelligence Core: the product-neutral parts of evidence checking, shared by VeriFact, NewsFact,
 * LegalFact and ResearchFact. Small stateless helpers over plain records; products keep their own prompts,
 * model output shapes, storage and wording, and decide how to combine these parts.
 *
 * <ul>
 *   <li>{@code provenance}: citations that must be a source's own words</li>
 *   <li>{@code claims}: grounding model-proposed claims in the submitted text ({@code ClaimGrounder}, {@code ClaimProfile})</li>
 *   <li>{@code assess}: the shared verdict taxonomy and the rules that let a verdict stand</li>
 * </ul>
 *
 * Nothing here may depend on a product package ({@code CoreBoundaryTest}).
 */
package com.ai.agent.verifact.core;
