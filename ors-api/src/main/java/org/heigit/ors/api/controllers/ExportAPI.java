/*
 * This file is part of Openrouteservice.
 *
 * Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library;
 * if not, see <https://www.gnu.org/licenses/>.
 */

package org.heigit.ors.api.controllers;

import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.heigit.ors.api.APIEnums;
import org.heigit.ors.api.errors.CommonResponseEntityExceptionHandler;
import org.heigit.ors.api.requests.export.ExportApiRequest;
import org.heigit.ors.api.responses.export.json.JsonExportResponse;
import org.heigit.ors.api.responses.export.topojson.TopoJsonExportResponse;
import org.heigit.ors.api.services.ExportService;
import org.heigit.ors.common.EncoderNameEnum;
import org.heigit.ors.exceptions.*;
import org.heigit.ors.export.ExportErrorCodes;
import org.heigit.ors.export.ExportResult;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Export Service", description = "Export the base graph for different modes of transport")
@RequestMapping("/v2/export")
@ApiResponse(responseCode = "400", description = "The request is incorrect and therefore can not be processed.")
@ApiResponse(responseCode = "404", description = "An element could not be found. If possible, a more detailed error code is provided.")
@ApiResponse(responseCode = "405", description = "The specified HTTP method is not supported. For more details, refer to the EndPoint documentation.")
@ApiResponse(responseCode = "413", description = "The request is larger than the server is able to process, the data provided in the request exceeds the capacity limit.")
@ApiResponse(responseCode = "500", description = "An unexpected error was encountered and a more detailed error code is provided.")
@ApiResponse(responseCode = "501", description = "Indicates that the server does not support the functionality needed to fulfill the request.")
@ApiResponse(responseCode = "503", description = "The server is currently unavailable due to overload or maintenance.")
public class ExportAPI {
    static final CommonResponseEntityExceptionHandler errorHandler = new CommonResponseEntityExceptionHandler(ExportErrorCodes.BASE);

    private final ExportService exportService;

    public ExportAPI(ExportService exportService) {
        this.exportService = exportService;
    }

    // generic catch methods - when extra info is provided in the url, the other methods are accessed.
    @GetMapping
    @Operation(hidden = true)
    public void getGetMapping() throws MissingParameterException {
        throw new MissingParameterException(ExportErrorCodes.MISSING_PARAMETER, "profile");
    }

    @PostMapping
    @Operation(hidden = true)
    public String getPostMapping(@RequestBody ExportApiRequest request) throws MissingParameterException {
        throw new MissingParameterException(ExportErrorCodes.MISSING_PARAMETER, "profile");
    }

    // Functional request methods
    @PostMapping(value = "/{profile}")
    @Operation(
            description = """
                    Returns a list of points, edges and weights within a given bounding box for a selected profile as JSON. \
                    This method does not accept any request body or parameters other than profile, start coordinate, and end coordinate.\
                    """,
            summary = "Export Service"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Standard response for successfully processed requests. Returns JSON.",
            content = {@Content(
                    mediaType = "application/geo+json",
                    schema = @Schema(implementation = JsonExportResponse.class)
            )
            })
    public JsonExportResponse getDefault(@Parameter(description = "Specifies the route profile.", required = true, example = "driving-car") @PathVariable String profile,
                                         @Parameter(description = "The request payload", required = true) @RequestBody ExportApiRequest request) throws StatusCodeException {
        return getJsonExport(profile, request);
    }

    @PostMapping(value = "/{profile}/json", produces = {"application/json;charset=UTF-8"})
    @Operation(
            description = "Returns a list of points, edges and weights within a given bounding box for a selected profile as JSON.",
            summary = "Export Service JSON"
    )
    @ApiResponse(
            responseCode = "200",
            description = "JSON Response.",
            content = {@Content(
                    mediaType = "application/geo+json",
                    schema = @Schema(implementation = JsonExportResponse.class)
            )
            })
    public JsonExportResponse getJsonExport(
            @Parameter(description = "Specifies the profile.", required = true, example = "driving-car") @PathVariable String profile,
            @Parameter(description = "The request payload", required = true) @RequestBody ExportApiRequest request) throws StatusCodeException {
        request.setProfile(getProfileEnum(profile));
        request.setProfileName(profile);
        request.setResponseType(APIEnums.ExportResponseType.JSON);

        ExportResult result = exportService.generateExportFromRequest(request);

        return new JsonExportResponse(result);
    }

    @PostMapping(value = "/{profile}/topojson", produces = {"application/json;charset=UTF-8"})
    @Operation(
            description = "Returns a list of edges, edge properties, and their topology within a given bounding box for a selected profile.",
            summary = "Export Service TopoJSON"
    )
    @ApiResponse(
            responseCode = "200",
            description = "TopoJSON Response.",
            content = {@Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TopoJsonExportResponse.class)
            )
            })
    public TopoJsonExportResponse getTopoJsonExport(
            @Parameter(description = "Specifies the profile.", required = true, example = "driving-car") @PathVariable String profile,
            @Parameter(description = "The request payload", required = true) @RequestBody ExportApiRequest request) throws StatusCodeException {
        request.setProfile(getProfileEnum(profile));
        request.setProfileName(profile);
        request.setResponseType(APIEnums.ExportResponseType.TOPOJSON);

        ExportResult result = exportService.generateExportFromRequest(request);

        return TopoJsonExportResponse.fromExportResult(result);
    }

    // Matches any response type that has not been defined
    @PostMapping(value = "/{profile}/{responseType}")
    @Operation(hidden = true)
    public void getInvalidResponseType(@PathVariable String profile, @PathVariable String responseType) throws StatusCodeException {
        if (responseType.equals("topojson")) {
            throw new StatusCodeException(HttpServletResponse.SC_NOT_ACCEPTABLE, ExportErrorCodes.UNSUPPORTED_EXPORT_FORMAT, "The response format topojson requires 'application/json' as accept and content-type headers.");

        } else {
            throw new StatusCodeException(HttpServletResponse.SC_NOT_ACCEPTABLE, ExportErrorCodes.UNSUPPORTED_EXPORT_FORMAT, "The response format %s is not supported".formatted(responseType));
        }
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParams(final MissingServletRequestParameterException e) {
        return errorHandler.handleStatusCodeException(new MissingParameterException(ExportErrorCodes.MISSING_PARAMETER, e.getParameterName()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMessageConversionException.class, Exception.class})
    public ResponseEntity<Object> handleReadingBodyException(final Exception e) {
        final Throwable cause = e.getCause();
        if (cause instanceof UnrecognizedPropertyException exception) {
            return errorHandler.handleUnknownParameterException(new UnknownParameterException(ExportErrorCodes.UNKNOWN_PARAMETER, exception.getPropertyName()));
        } else if (cause instanceof InvalidFormatException exception) {
            return errorHandler.handleStatusCodeException(new ParameterValueException(ExportErrorCodes.INVALID_PARAMETER_FORMAT, exception.getValue().toString()));
        } else if (cause instanceof InvalidDefinitionException exception) {
            return errorHandler.handleStatusCodeException(new ParameterValueException(ExportErrorCodes.INVALID_PARAMETER_VALUE, exception.getPath().get(0).getFieldName()));
        } else if (cause instanceof MismatchedInputException exception) {
            return errorHandler.handleStatusCodeException(new ParameterValueException(ExportErrorCodes.MISMATCHED_INPUT, exception.getPath().get(0).getFieldName()));
        } else {
            // Check if we are missing the body as a whole
            if (e.getLocalizedMessage() != null && e.getLocalizedMessage().startsWith("Required request body is missing")) {
                return errorHandler.handleStatusCodeException(new EmptyElementException(ExportErrorCodes.MISSING_PARAMETER, "Request body could not be read"));
            }
            return errorHandler.handleGenericException(e);
        }
    }

    @ExceptionHandler(StatusCodeException.class)
    public ResponseEntity<Object> handleException(final StatusCodeException e) {
        return errorHandler.handleStatusCodeException(e);
    }

    private APIEnums.Profile getProfileEnum(String profile) throws ParameterValueException {
        EncoderNameEnum encoderForProfile = exportService.getEncoderForProfile(profile);
        return APIEnums.Profile.forValue(encoderForProfile.getEncoderName());
    }
}
